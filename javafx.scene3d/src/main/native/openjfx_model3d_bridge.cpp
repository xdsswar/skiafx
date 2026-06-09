// openjfx_model3d — native glTF 2.0 asset parser for the javafx.scene3d module.
//
// skia-fx original work (no upstream OpenJFX provenance — stock JavaFX has no
// model loader). This library does NOT depend on Skia, bgfx, or D3D: it only
// parses glTF and decodes accessor data into plain float/int arrays that the
// Java side copies into standard javafx.scene.shape.TriangleMesh /
// javafx.scene.paint.PhongMaterial objects. Those then render through the
// existing Door-1 3D pipeline in javafx.graphics. The public Java API never
// sees a native handle (CLAUDE.md / memory no-ids-handles-in-public-api).
//
// ABI contract (mirrors the discipline of the 3D bridge in javafx.graphics):
//   * Every entry point validates its session handle (magic tag) before any
//     dereference. A null / wild / already-closed handle returns an error code
//     and is NEVER dereferenced.
//   * Errors degrade — functions return negative codes (or 0 counts); nothing
//     throws across the C ABI and nothing aborts the process
//     (memory errors-never-kill-jvm).
//   * Geometry/texture copies are caller-allocated: Java first queries counts,
//     sizes them, then asks us to fill its arrays. No native buffer escapes to
//     Java; the only thing handed back is plain data the JVM owns.
//
// Cross-platform: pure portable C++17 + cgltf. The only platform-specific line
// is the symbol-export macro below.

#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

// cgltf is a single-header library; define the implementation in exactly this
// one translation unit. See third_party/cgltf/PROVENANCE.txt.
#define CGLTF_IMPLEMENTATION
#include "third_party/cgltf/cgltf.h"

#if defined(_WIN32)
  #define M3D_EXPORT extern "C" __declspec(dllexport)
#else
  #define M3D_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {

// Distinct per-type magic, poisoned to 0 on close so a freed / double-closed /
// wild handle is rejected by asSession() before any field access.
constexpr uint32_t kSessionMagic = 0x4D33444C; // 'M3DL'

// One parsed glTF document. cgltf does NOT copy its input — for a .glb it keeps
// pointers into the source bytes (the JSON and the embedded BIN chunk) for the
// lifetime of cgltf_data. So the session owns its OWN copy of the input buffer
// (fileData) and parses from that; the caller's buffer can be freed the moment
// model3d_open returns. (Image bytes base64-decoded from data: URIs are NOT
// session-owned — they are decoded and freed within a single material-texture
// query; see model3d_material_texture.)
struct Session {
    uint32_t magic = kSessionMagic;
    cgltf_data* data = nullptr;
    void* fileData = nullptr;                   // session-owned copy of the input
    std::string baseDir;                       // for resolving external URIs
};

// Validate a handle: non-null and carrying our live magic. Returns nullptr for
// anything else (null, garbage pointer with the wrong tag, or a poisoned
// already-closed session).
inline Session* asSession(uintptr_t h) {
    if (h == 0) {
        return nullptr;
    }
    Session* s = reinterpret_cast<Session*>(h);
    if (s->magic != kSessionMagic) {
        return nullptr;
    }
    return s;
}

// Texture slot selectors shared with the Java side (NativeModelBridge).
enum TexType {
    TEX_BASE_COLOR = 0,
    TEX_NORMAL     = 1,
    TEX_EMISSIVE   = 2,
    TEX_METAL_ROUGH = 3,
};

// Texture source kinds returned to Java.
enum TexKind {
    KIND_NONE     = 0,
    KIND_EMBEDDED = 1, // raw PNG/JPG bytes available in-process
    KIND_URI      = 2, // external file path (resolve against baseDir)
};

// Pick the texture_view for a given slot, or nullptr if the material lacks it.
const cgltf_texture_view* texViewFor(const cgltf_material* m, int type) {
    if (m == nullptr) {
        return nullptr;
    }
    switch (type) {
        case TEX_BASE_COLOR:
            return m->has_pbr_metallic_roughness
                ? &m->pbr_metallic_roughness.base_color_texture : nullptr;
        case TEX_METAL_ROUGH:
            return m->has_pbr_metallic_roughness
                ? &m->pbr_metallic_roughness.metallic_roughness_texture : nullptr;
        case TEX_NORMAL:
            return &m->normal_texture;
        case TEX_EMISSIVE:
            return &m->emissive_texture;
        default:
            return nullptr;
    }
}

// Copy a (possibly null) C string into a caller buffer using the same two-call
// protocol as the texture query: pass dst==null/cap==0 to learn the needed
// length (string length + 1 for the NUL), then call again with a big-enough
// buffer. A null name is reported as the empty string.
int64_t copyName(const char* name, char* dst, int64_t cap) {
    if (name == nullptr) {
        name = "";
    }
    int64_t needed = static_cast<int64_t>(std::strlen(name)) + 1; // include NUL
    if (dst != nullptr && cap >= needed) {
        std::memcpy(dst, name, static_cast<size_t>(needed));
    }
    return needed;
}

// Base64 decoded-size estimate for a data: URI payload (chars after the comma).
size_t base64DecodedSize(const char* b64, size_t n) {
    if (n == 0) {
        return 0;
    }
    size_t pad = 0;
    if (b64[n - 1] == '=') { pad++; }
    if (n >= 2 && b64[n - 2] == '=') { pad++; }
    size_t bytes = (n / 4) * 3;
    if (pad != 0) {
        // Properly padded: n is a multiple of 4; subtract the padding bytes.
        bytes -= pad;
    } else {
        // Unpadded payload (legal in data: URIs — exporters often omit '='):
        // a trailing 2-char group decodes to 1 byte, a 3-char group to 2 bytes.
        // Without this the final partial group is dropped and cgltf is asked to
        // decode too few bytes, so the embedded image comes out truncated/blank.
        size_t rem = n % 4;
        if (rem == 2) { bytes += 1; }
        else if (rem == 3) { bytes += 2; }
    }
    return bytes;
}

// Resolve a texture slot to either embedded bytes (decoding a data: URI on the
// fly if needed) or an external URI string. Returns the kind; on KIND_EMBEDDED
// sets *outPtr/*outLen, on KIND_URI sets *outUri (a std::string, decoded). The
// embedded pointer is valid until model3d_close. *outBlobToFree, when non-null,
// must be freed by the caller after copying (a freshly base64-decoded blob).
int resolveTexture(Session* s, const cgltf_material* m, int type,
                   const uint8_t** outPtr, size_t* outLen,
                   std::string* outUri, void** outBlobToFree) {
    *outPtr = nullptr;
    *outLen = 0;
    *outBlobToFree = nullptr;
    outUri->clear();

    const cgltf_texture_view* view = texViewFor(m, type);
    if (view == nullptr || view->texture == nullptr) {
        return KIND_NONE;
    }
    const cgltf_image* img = view->texture->image;
    if (img == nullptr) {
        // basisu / KTX2 images are unsupported here (the JavaFX Image decoder
        // can't read them) — treat as absent so the material keeps its color.
        return KIND_NONE;
    }

    // 1. Embedded via a buffer view (the .glb case, and .gltf with a
    //    GLB-buffer image). cgltf_buffer_view_data already accounts for the
    //    view offset and any extension-provided data override.
    if (img->buffer_view != nullptr) {
        const uint8_t* p = cgltf_buffer_view_data(img->buffer_view);
        if (p != nullptr && img->buffer_view->size > 0) {
            *outPtr = p;
            *outLen = img->buffer_view->size;
            return KIND_EMBEDDED;
        }
        return KIND_NONE;
    }

    if (img->uri == nullptr) {
        return KIND_NONE;
    }

    // 2. data: URI — decode the base64 payload to raw image bytes.
    if (std::strncmp(img->uri, "data:", 5) == 0) {
        const char* comma = std::strchr(img->uri, ',');
        if (comma == nullptr) {
            return KIND_NONE;
        }
        const char* b64 = comma + 1;
        size_t b64Len = std::strlen(b64);
        size_t decoded = base64DecodedSize(b64, b64Len);
        if (decoded == 0) {
            return KIND_NONE;
        }
        cgltf_options opts;
        std::memset(&opts, 0, sizeof(opts));
        void* out = nullptr;
        if (cgltf_load_buffer_base64(&opts, decoded, b64, &out) != cgltf_result_success
            || out == nullptr) {
            return KIND_NONE;
        }
        *outPtr = reinterpret_cast<const uint8_t*>(out);
        *outLen = decoded;
        *outBlobToFree = out; // caller copies then frees
        return KIND_EMBEDDED;
    }

    // 3. External file URI — hand back a percent-decoded path; Java resolves it
    //    against baseDir. (We decode a COPY so cgltf's string stays pristine.)
    std::string uri(img->uri);
    cgltf_decode_uri(&uri[0]);
    uri.resize(std::strlen(uri.c_str())); // decode_uri may shorten in place
    *outUri = uri;
    return KIND_URI;
}

} // anonymous namespace

// ---------------------------------------------------------------------------
// Public C ABI (FFM target). All names are flat extern "C" symbols.
// ---------------------------------------------------------------------------

// Probe symbol — lets the Java bridge confirm the library loaded.
M3D_EXPORT int model3d_version() {
    return 1;
}

// Parse a .glb/.gltf blob already read into memory. baseDir (may be null) is
// the directory used to resolve external buffer/image URIs. Returns an opaque
// session handle or 0 on any failure (never throws/crashes).
M3D_EXPORT uintptr_t model3d_open(const uint8_t* data, int64_t len, const char* baseDir) {
    if (data == nullptr || len <= 0) {
        return 0;
    }
    // Own a copy of the input — cgltf keeps pointers into it (GLB bin chunk,
    // JSON) for the cgltf_data lifetime, so the caller's buffer must not be the
    // backing store. Freed in model3d_close after cgltf_free.
    size_t n = static_cast<size_t>(len);
    void* fileCopy = std::malloc(n);
    if (fileCopy == nullptr) {
        return 0;
    }
    std::memcpy(fileCopy, data, n);

    cgltf_options options;
    std::memset(&options, 0, sizeof(options));

    cgltf_data* parsed = nullptr;
    if (cgltf_parse(&options, fileCopy, n, &parsed) != cgltf_result_success) {
        std::free(fileCopy);
        return 0;
    }
    // Load buffers (resolves data: URIs and, for external .bin, files under
    // baseDir). cgltf reads external files relative to the path we pass.
    std::string base = (baseDir != nullptr) ? baseDir : "";
    std::string loadPath = base.empty() ? std::string() : (base + "/_");
    if (cgltf_load_buffers(&options, parsed, loadPath.empty() ? nullptr : loadPath.c_str())
            != cgltf_result_success) {
        cgltf_free(parsed);
        std::free(fileCopy);
        return 0;
    }
    if (cgltf_validate(parsed) != cgltf_result_success) {
        cgltf_free(parsed);
        std::free(fileCopy);
        return 0;
    }

    Session* s = new (std::nothrow) Session();
    if (s == nullptr) {
        cgltf_free(parsed);
        std::free(fileCopy);
        return 0;
    }
    s->data = parsed;
    s->fileData = fileCopy;
    s->baseDir = base;
    return reinterpret_cast<uintptr_t>(s);
}

// Release a session. Poisons the magic first so a concurrent/duplicate close or
// any later use is a guarded no-op rather than a use-after-free.
M3D_EXPORT void model3d_close(uintptr_t h) {
    Session* s = asSession(h);
    if (s == nullptr) {
        return; // null, wild, or already-closed — safe no-op
    }
    s->magic = 0; // poison before freeing
    if (s->data != nullptr) {
        cgltf_free(s->data);
        s->data = nullptr;
    }
    if (s->fileData != nullptr) {
        std::free(s->fileData); // freed AFTER cgltf_free (cgltf points into it)
        s->fileData = nullptr;
    }
    delete s;
}

// ---- Meshes / primitives --------------------------------------------------

M3D_EXPORT int model3d_mesh_count(uintptr_t h) {
    Session* s = asSession(h);
    return (s == nullptr) ? -1 : static_cast<int>(s->data->meshes_count);
}

M3D_EXPORT int model3d_primitive_count(uintptr_t h, int meshIdx) {
    Session* s = asSession(h);
    if (s == nullptr || meshIdx < 0 || meshIdx >= static_cast<int>(s->data->meshes_count)) {
        return -1;
    }
    return static_cast<int>(s->data->meshes[meshIdx].primitives_count);
}

// JavaFX VertexFormat selector returned in *outFormat. Matches the two formats
// javafx.scene.shape.VertexFormat exposes.
enum VertexFormatCode {
    FMT_POINT_TEXCOORD        = 0, // 6 ints/triangle: p,t  p,t  p,t
    FMT_POINT_NORMAL_TEXCOORD = 1, // 9 ints/triangle: p,n,t  p,n,t  p,n,t
};

// Flags returned in *outFlags from model3d_primitive_info.
enum PrimFlags {
    PRIM_HAS_NORMALS   = 1 << 0,
    PRIM_HAS_TEXCOORDS = 1 << 1,
    PRIM_IS_TRIANGLES  = 1 << 2,
};

// Locate the POSITION / NORMAL / TEXCOORD_0 accessors of a primitive.
void primitiveAccessors(const cgltf_primitive& prim,
                        const cgltf_accessor** pos,
                        const cgltf_accessor** nrm,
                        const cgltf_accessor** uv) {
    *pos = nullptr; *nrm = nullptr; *uv = nullptr;
    for (cgltf_size a = 0; a < prim.attributes_count; ++a) {
        const cgltf_attribute& attr = prim.attributes[a];
        if (attr.type == cgltf_attribute_type_position) {
            *pos = attr.data;
        } else if (attr.type == cgltf_attribute_type_normal) {
            *nrm = attr.data;
        } else if (attr.type == cgltf_attribute_type_texcoord && attr.index == 0) {
            *uv = attr.data; // TEXCOORD_0 only this increment
        }
    }
}

// Renderable-triangle counts for a primitive, validated to fit a 32-bit int.
// Returns false (with *outVtx/*outFaceCount = 0) when the primitive is not a
// triangle list, has no POSITION, has a non-multiple-of-3 index count, or its
// counts would overflow int — the Java side sizes its arrays with int math
// (points = vtx*3, faces = faceCount*9), so an int-overflowing count there would
// wrap to an under-sized buffer and the native fill would overrun it.
//
// model3d_primitive_info AND model3d_primitive_build BOTH derive their counts
// here, so they can never disagree (a disagreement would write more faces than
// the Java buffer was sized for).
bool triangleCounts(const cgltf_primitive& prim, const cgltf_accessor* pos,
                    cgltf_size* outVtx, cgltf_size* outFaceCount) {
    *outVtx = 0;
    *outFaceCount = 0;
    if (pos == nullptr || prim.type != cgltf_primitive_type_triangles) {
        return false;
    }
    cgltf_size vtx = pos->count;
    cgltf_size idxCount = (prim.indices != nullptr) ? prim.indices->count : vtx;
    if ((idxCount % 3) != 0) {
        return false; // not a whole number of triangles
    }
    cgltf_size faceCount = idxCount / 3;
    const cgltf_size kIntMax = static_cast<cgltf_size>(0x7fffffff);
    if (vtx > kIntMax / 3 || faceCount > kIntMax / 9) {
        return false; // would overflow the Java int array sizing
    }
    *outVtx = vtx;
    *outFaceCount = faceCount;
    return true;
}

// Describe one primitive so Java can size its TriangleMesh arrays and pick the
// VertexFormat. Returns 0 on success, negative on a bad handle/index.
//   *outVtx       = vertex count (POSITION accessor count)
//   *outFaceCount = triangle count (0 if the primitive is not TRIANGLES or has a
//                   non-multiple-of-3 index count — Java then skips it)
//   *outFormat    = FMT_POINT_NORMAL_TEXCOORD when NORMAL is present, else
//                   FMT_POINT_TEXCOORD (renderer generates smooth normals)
//   *outMat       = material index or -1
//   *outFlags     = PrimFlags bitset
M3D_EXPORT int model3d_primitive_info(uintptr_t h, int meshIdx, int primIdx,
                                      int* outVtx, int* outFaceCount,
                                      int* outFormat, int* outMat, int* outFlags) {
    Session* s = asSession(h);
    if (s == nullptr || outVtx == nullptr || outFaceCount == nullptr
        || outFormat == nullptr || outMat == nullptr || outFlags == nullptr) {
        return -1;
    }
    if (meshIdx < 0 || meshIdx >= static_cast<int>(s->data->meshes_count)) {
        return -2;
    }
    const cgltf_mesh& mesh = s->data->meshes[meshIdx];
    if (primIdx < 0 || primIdx >= static_cast<int>(mesh.primitives_count)) {
        return -3;
    }
    const cgltf_primitive& prim = mesh.primitives[primIdx];

    const cgltf_accessor *pos, *nrm, *uv;
    primitiveAccessors(prim, &pos, &nrm, &uv);

    int flags = 0;
    if (nrm != nullptr) { flags |= PRIM_HAS_NORMALS; }
    if (uv  != nullptr) { flags |= PRIM_HAS_TEXCOORDS; }
    bool triangles = (prim.type == cgltf_primitive_type_triangles);
    if (triangles) { flags |= PRIM_IS_TRIANGLES; }

    // Validated, int-safe counts (0 when the primitive isn't a renderable
    // triangle list); both vtx and faceCount fit in int here, so the casts below
    // can't truncate. Java skips the primitive when faceCount <= 0.
    cgltf_size vtxSz = 0, faceSz = 0;
    triangleCounts(prim, pos, &vtxSz, &faceSz);

    *outVtx = static_cast<int>(vtxSz);
    *outFaceCount = static_cast<int>(faceSz);
    *outFormat = (nrm != nullptr) ? FMT_POINT_NORMAL_TEXCOORD : FMT_POINT_TEXCOORD;
    *outMat = (prim.material != nullptr)
        ? static_cast<int>(cgltf_material_index(s->data, prim.material)) : -1;
    *outFlags = flags;
    return 0;
}

// Build the JavaFX-ready geometry for one primitive — the heavy per-vertex /
// per-face work lives here in C++, so the Java side only stuffs the finished
// arrays into a TriangleMesh. Caller sizes the arrays from model3d_primitive_info:
//   points    : vtx*3 floats (always written)
//   normals   : vtx*3 floats — written only when format == FMT_POINT_NORMAL_TEXCOORD
//               (pass null otherwise)
//   texcoords : vtx*2 floats (always written; zero-filled when the primitive has
//               no TEXCOORD_0, so the face texcoord indices stay valid)
//   faces     : faceCount * (format==PNT ? 9 : 6) ints, already interleaved in
//               JavaFX layout. Per-channel index == vertex index (the mesh is
//               de-indexed per-vertex: points/normals/texcoords are all parallel).
//
// Geometry is emitted in glTF's native space (right-handed, +Y up, top-left UV
// origin). The Java builder applies the JavaFX Y-flip via a determinant-negative
// root Transform — NOT by rewriting vertices here — so face winding is preserved
// and culling auto-corrects. Returns 0 on success.
M3D_EXPORT int model3d_primitive_build(uintptr_t h, int meshIdx, int primIdx,
                                       float* points, float* normals,
                                       float* texcoords, int32_t* faces) {
    Session* s = asSession(h);
    if (s == nullptr) {
        return -1;
    }
    if (meshIdx < 0 || meshIdx >= static_cast<int>(s->data->meshes_count)) {
        return -2;
    }
    const cgltf_mesh& mesh = s->data->meshes[meshIdx];
    if (primIdx < 0 || primIdx >= static_cast<int>(mesh.primitives_count)) {
        return -3;
    }
    const cgltf_primitive& prim = mesh.primitives[primIdx];

    const cgltf_accessor *pos_a, *nrm_a, *uv_a;
    primitiveAccessors(prim, &pos_a, &nrm_a, &uv_a);
    if (pos_a == nullptr) {
        return -4;
    }
    // Same validated counts model3d_primitive_info reported, so the writes below
    // exactly fill the Java-allocated arrays (and never overrun on a degenerate
    // or non-multiple-of-3 primitive).
    cgltf_size vtx = 0, faceCount = 0;
    if (!triangleCounts(prim, pos_a, &vtx, &faceCount)) {
        return -5; // not a renderable triangle list — Java should have skipped it
    }

    // cgltf_accessor_unpack_floats handles sparse data, normalization, the
    // component type and any interleaved stride — it always writes a tightly
    // packed run of count*num_components floats.
    if (points != nullptr) {
        cgltf_accessor_unpack_floats(pos_a, points, vtx * 3);
    }
    if (normals != nullptr && nrm_a != nullptr) {
        cgltf_accessor_unpack_floats(nrm_a, normals, vtx * 3);
    }
    if (texcoords != nullptr) {
        if (uv_a != nullptr) {
            cgltf_accessor_unpack_floats(uv_a, texcoords, vtx * 2);
        } else {
            std::memset(texcoords, 0, sizeof(float) * vtx * 2); // valid 0,0 set
        }
    }

    // Interleave the faces array in JavaFX layout. The three per-channel indices
    // for a vertex are all the same (we ship parallel per-vertex arrays), so a
    // glTF vertex index v becomes [v,v,v] (PNT) or [v,v] (PT). faceCount is the
    // exact triangle count the Java buffer was sized for.
    if (faces != nullptr) {
        bool hasNormals = (nrm_a != nullptr);
        cgltf_size w = 0;
        for (cgltf_size t = 0; t < faceCount; ++t) {
            for (int k = 0; k < 3; ++k) {
                cgltf_size flat = t * 3 + k;
                int32_t v = (prim.indices != nullptr)
                    ? static_cast<int32_t>(cgltf_accessor_read_index(prim.indices, flat))
                    : static_cast<int32_t>(flat);
                if (hasNormals) {
                    faces[w++] = v; // point index
                    faces[w++] = v; // normal index
                    faces[w++] = v; // texcoord index
                } else {
                    faces[w++] = v; // point index
                    faces[w++] = v; // texcoord index
                }
            }
        }
    }
    return 0;
}

// ---- Materials ------------------------------------------------------------

M3D_EXPORT int model3d_material_count(uintptr_t h) {
    Session* s = asSession(h);
    return (s == nullptr) ? -1 : static_cast<int>(s->data->materials_count);
}

// Material scalar parameters. out9 receives
//   [baseR, baseG, baseB, baseA, metallic, roughness, emissiveR, emissiveG, emissiveB].
// outFlags bit0 = double-sided. Returns 0 on success.
M3D_EXPORT int model3d_material_info(uintptr_t h, int matIdx, float* out9, int* outFlags) {
    Session* s = asSession(h);
    if (s == nullptr || out9 == nullptr || outFlags == nullptr) {
        return -1;
    }
    if (matIdx < 0 || matIdx >= static_cast<int>(s->data->materials_count)) {
        return -2;
    }
    const cgltf_material& m = s->data->materials[matIdx];

    // Defaults (glTF spec): white opaque base, full roughness, no emission.
    float base[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    float metallic = 1.0f;
    float roughness = 1.0f;
    if (m.has_pbr_metallic_roughness) {
        const cgltf_pbr_metallic_roughness& p = m.pbr_metallic_roughness;
        base[0] = p.base_color_factor[0];
        base[1] = p.base_color_factor[1];
        base[2] = p.base_color_factor[2];
        base[3] = p.base_color_factor[3];
        metallic = p.metallic_factor;
        roughness = p.roughness_factor;
    }
    out9[0] = base[0]; out9[1] = base[1]; out9[2] = base[2]; out9[3] = base[3];
    out9[4] = metallic;
    out9[5] = roughness;
    out9[6] = m.emissive_factor[0];
    out9[7] = m.emissive_factor[1];
    out9[8] = m.emissive_factor[2];
    *outFlags = m.double_sided ? 1 : 0;
    return 0;
}

// Texture slot query. Two-call protocol so Java owns the memory:
//   * call with dst == null / cap == 0 to learn *outKind and the needed length
//     (image byte count for KIND_EMBEDDED, or URI string length+1 for KIND_URI);
//   * call again with a buffer of at least that length to receive the data.
// Returns the needed length (>= 0), or negative on a bad handle/index. KIND_URI
// writes a NUL-terminated UTF-8 path; KIND_EMBEDDED writes raw PNG/JPG bytes.
M3D_EXPORT int64_t model3d_material_texture(uintptr_t h, int matIdx, int type,
                                            int* outKind, uint8_t* dst, int64_t cap) {
    Session* s = asSession(h);
    if (s == nullptr || outKind == nullptr) {
        return -1;
    }
    if (matIdx < 0 || matIdx >= static_cast<int>(s->data->materials_count)) {
        return -2;
    }
    const cgltf_material& m = s->data->materials[matIdx];

    const uint8_t* ptr = nullptr;
    size_t len = 0;
    std::string uri;
    void* blobToFree = nullptr;
    int kind = resolveTexture(s, &m, type, &ptr, &len, &uri, &blobToFree);
    *outKind = kind;

    int64_t needed = 0;
    if (kind == KIND_EMBEDDED) {
        needed = static_cast<int64_t>(len);
        if (dst != nullptr && cap >= needed) {
            std::memcpy(dst, ptr, len);
        }
    } else if (kind == KIND_URI) {
        needed = static_cast<int64_t>(uri.size()) + 1; // include NUL
        if (dst != nullptr && cap >= needed) {
            std::memcpy(dst, uri.c_str(), uri.size() + 1);
        }
    }
    if (blobToFree != nullptr) {
        std::free(blobToFree); // decoded fresh each call; bounded + simple
    }
    return needed;
}

// ---- Node hierarchy -------------------------------------------------------

M3D_EXPORT int model3d_node_count(uintptr_t h) {
    Session* s = asSession(h);
    return (s == nullptr) ? -1 : static_cast<int>(s->data->nodes_count);
}

// Local TRS (or explicit matrix) of a node as a 16-float column-major matrix.
M3D_EXPORT int model3d_node_local_matrix(uintptr_t h, int nodeIdx, float* m16) {
    Session* s = asSession(h);
    if (s == nullptr || m16 == nullptr) {
        return -1;
    }
    if (nodeIdx < 0 || nodeIdx >= static_cast<int>(s->data->nodes_count)) {
        return -2;
    }
    cgltf_node_transform_local(&s->data->nodes[nodeIdx], m16);
    return 0;
}

// Mesh index attached to a node, or -1 if none.
M3D_EXPORT int model3d_node_mesh(uintptr_t h, int nodeIdx) {
    Session* s = asSession(h);
    if (s == nullptr || nodeIdx < 0 || nodeIdx >= static_cast<int>(s->data->nodes_count)) {
        return -1;
    }
    const cgltf_node& n = s->data->nodes[nodeIdx];
    return (n.mesh != nullptr) ? static_cast<int>(cgltf_mesh_index(s->data, n.mesh)) : -1;
}

M3D_EXPORT int model3d_node_child_count(uintptr_t h, int nodeIdx) {
    Session* s = asSession(h);
    if (s == nullptr || nodeIdx < 0 || nodeIdx >= static_cast<int>(s->data->nodes_count)) {
        return -1;
    }
    return static_cast<int>(s->data->nodes[nodeIdx].children_count);
}

M3D_EXPORT int model3d_node_child(uintptr_t h, int nodeIdx, int k) {
    Session* s = asSession(h);
    if (s == nullptr || nodeIdx < 0 || nodeIdx >= static_cast<int>(s->data->nodes_count)) {
        return -1;
    }
    const cgltf_node& n = s->data->nodes[nodeIdx];
    if (k < 0 || k >= static_cast<int>(n.children_count)) {
        return -1;
    }
    return static_cast<int>(cgltf_node_index(s->data, n.children[k]));
}

// Root nodes of the default scene (or, lacking a scene, every parent-less node).
// Java walks these to build the Group hierarchy.
M3D_EXPORT int model3d_root_count(uintptr_t h) {
    Session* s = asSession(h);
    if (s == nullptr) {
        return -1;
    }
    if (s->data->scene != nullptr) {
        return static_cast<int>(s->data->scene->nodes_count);
    }
    int roots = 0;
    for (cgltf_size i = 0; i < s->data->nodes_count; ++i) {
        if (s->data->nodes[i].parent == nullptr) {
            roots++;
        }
    }
    return roots;
}

M3D_EXPORT int model3d_root(uintptr_t h, int k) {
    Session* s = asSession(h);
    if (s == nullptr || k < 0) {
        return -1;
    }
    if (s->data->scene != nullptr) {
        if (k >= static_cast<int>(s->data->scene->nodes_count)) {
            return -1;
        }
        return static_cast<int>(cgltf_node_index(s->data, s->data->scene->nodes[k]));
    }
    int seen = 0;
    for (cgltf_size i = 0; i < s->data->nodes_count; ++i) {
        if (s->data->nodes[i].parent == nullptr) {
            if (seen == k) {
                return static_cast<int>(i);
            }
            seen++;
        }
    }
    return -1;
}

// ---- Names (two-call protocol; see copyName) ------------------------------
// Carrying glTF names lets the Java side stamp node IDs so tools introspect a
// loaded model with standard JavaFX lookup. Each returns the needed length
// (string length + 1), or a negative code on a bad handle/index.

M3D_EXPORT int64_t model3d_node_name(uintptr_t h, int nodeIdx, char* dst, int64_t cap) {
    Session* s = asSession(h);
    if (s == nullptr || nodeIdx < 0 || nodeIdx >= static_cast<int>(s->data->nodes_count)) {
        return -1;
    }
    return copyName(s->data->nodes[nodeIdx].name, dst, cap);
}

M3D_EXPORT int64_t model3d_mesh_name(uintptr_t h, int meshIdx, char* dst, int64_t cap) {
    Session* s = asSession(h);
    if (s == nullptr || meshIdx < 0 || meshIdx >= static_cast<int>(s->data->meshes_count)) {
        return -1;
    }
    return copyName(s->data->meshes[meshIdx].name, dst, cap);
}

// Name of the default scene (glTF has no single "model name"), or "" if unnamed.
M3D_EXPORT int64_t model3d_scene_name(uintptr_t h, char* dst, int64_t cap) {
    Session* s = asSession(h);
    if (s == nullptr) {
        return -1;
    }
    const char* nm = (s->data->scene != nullptr) ? s->data->scene->name : nullptr;
    return copyName(nm, dst, cap);
}
