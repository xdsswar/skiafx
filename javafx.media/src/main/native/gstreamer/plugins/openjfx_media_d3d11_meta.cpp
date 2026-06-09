// ---------------------------------------------------------------------------
// openjfx_media_d3d11_meta — implementation. See header for contract.
//
// This file is compiled INTO both fxplugins.dll (producer side, via
// mfwrapper) and jfxmedia.dll (consumer side, via GstVideoFrame). Each
// DLL therefore has its own non-inline copies of the functions, and its
// own static caches of the GType + GstMetaInfo*. Both copies call the
// same global GStreamer registry, which keys metas by their API name
// string, so they end up resolving to the SAME GstMetaInfo* at runtime
// — that's what lets the producer's attach and the consumer's get see
// the same meta data.
//
// Why not an inline-static header? An earlier iteration tried that
// pattern. On real Windows runs it caused heap corruption /
// access-violation crashes during the first frame's meta lookup — the
// likely culprit was function-local statics combined with
// g_once_init_enter being inlined across translation units, producing
// a TU-local static pointer that violated the once-init contract.
// Non-inline functions with file-scope statics avoid the pitfall.
// ---------------------------------------------------------------------------

#include "openjfx_media_d3d11_meta.h"

#ifdef _WIN32
#  include <unknwn.h> // IUnknown — used to AddRef / Release the texture
#endif

extern "C" {

static gboolean openjfx_media_d3d11_meta_init(
    GstMeta* meta, gpointer params, GstBuffer* buffer)
{
    (void)params; (void)buffer;
    OpenJfxMediaD3d11Meta* m = (OpenJfxMediaD3d11Meta*)meta;
    m->d3d11Texture = NULL;
    m->subresource  = 0;
    m->width        = 0;
    m->height       = 0;
    m->kind         = OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_NONE;
    return TRUE;
}

static void openjfx_media_d3d11_meta_free(
    GstMeta* meta, GstBuffer* buffer)
{
    (void)buffer;
    OpenJfxMediaD3d11Meta* m = (OpenJfxMediaD3d11Meta*)meta;
#ifdef _WIN32
    if (m->d3d11Texture) {
        ((IUnknown*)m->d3d11Texture)->Release();
        m->d3d11Texture = NULL;
    }
#else
    m->d3d11Texture = NULL;
#endif
}

static gboolean openjfx_media_d3d11_meta_transform(
    GstBuffer* transbuf, GstMeta* meta, GstBuffer* buffer,
    GQuark type, gpointer data)
{
    (void)buffer; (void)data;
    // Propagate on plain COPY transforms — GStreamer may make-writable
    // / shallow-copy the buffer between elements without changing the
    // pixel data, and we want the texture handle to ride along to the
    // sink so the consumer can use the zero-copy path.
    //
    // For any non-copy transform (e.g. real format conversion via
    // videoconvert), we DON'T propagate: the converted buffer no
    // longer matches what the texture contains.
    OpenJfxMediaD3d11Meta* src = (OpenJfxMediaD3d11Meta*)meta;
    if (GST_META_TRANSFORM_IS_COPY(type)) {
        OpenJfxMediaD3d11Meta* dst = (OpenJfxMediaD3d11Meta*)
            gst_buffer_add_meta(transbuf,
                openjfx_media_d3d11_meta_get_info(), NULL);
        if (!dst) return FALSE;
#ifdef _WIN32
        if (src->d3d11Texture) {
            ((IUnknown*)src->d3d11Texture)->AddRef();
        }
#endif
        dst->d3d11Texture = src->d3d11Texture;
        dst->subresource  = src->subresource;
        dst->width        = src->width;
        dst->height       = src->height;
        dst->kind         = src->kind;
        return TRUE;
    }
    return FALSE;
}

// One-time, single-call init. NOT using g_once_init_enter / atomics —
// the consumer's call site (CGstVideoFrame::Init) is single-threaded
// per pipeline, and the producer (mfwrapper) is on its own MF thread
// but doesn't race with the consumer's lookup since they're serialised
// through the GstBuffer queue. If two callers ever race in practice,
// gst_meta_api_type_register is itself idempotent by name and gst_meta_
// register dedupes too, so the worst case is a transient double init
// that converges.

GType openjfx_media_d3d11_meta_api_get_type(void)
{
    static GType cached = 0;
    if (cached != 0) return cached;

    // gst_meta_api_type_register is NOT idempotent the way intuition
    // suggests: it wraps g_pointer_type_register_static, which returns
    // G_TYPE_INVALID (0) on the *second* call with the same name —
    // the type is already registered, but the registration call fails.
    // In a multi-DLL build (both fxplugins and jfxmedia carry this
    // .cpp), the second loader's call always returns 0 unless we also
    // probe the existing type registry. Pattern: register; if that
    // failed AND the type already exists, look it up by name. Cache
    // the final value either way so we only run this dance once.
    static const gchar* tags[] = { NULL };
    GType t = gst_meta_api_type_register("OpenJfxMediaD3d11MetaAPI", tags);
    if (t == 0) {
        t = g_type_from_name("OpenJfxMediaD3d11MetaAPI");
    }
    cached = t;
    return cached;
}

const GstMetaInfo* openjfx_media_d3d11_meta_get_info(void)
{
    static const GstMetaInfo* cached = NULL;
    if (cached == NULL) {
        // CRITICAL: check the global registry first. gst_meta_register
        // is keyed by impl name and REPLACES (g_hash_table_insert) any
        // prior entry, freeing the old GstMetaInfo. With both fxplugins
        // and jfxmedia carrying their own copy of this .cpp, the
        // second registration would invalidate the first — and any
        // metas already attached to buffers in flight would be left
        // with dangling info pointers (heap corruption / crash).
        // gst_meta_get_info returns the existing entry if registered,
        // so the second TU re-uses it instead of stomping on it.
        cached = gst_meta_get_info("OpenJfxMediaD3d11Meta");
        if (cached == NULL) {
            cached = gst_meta_register(
                openjfx_media_d3d11_meta_api_get_type(),
                "OpenJfxMediaD3d11Meta",
                sizeof(OpenJfxMediaD3d11Meta),
                (GstMetaInitFunction)      openjfx_media_d3d11_meta_init,
                (GstMetaFreeFunction)      openjfx_media_d3d11_meta_free,
                (GstMetaTransformFunction) openjfx_media_d3d11_meta_transform);
        }
    }
    return cached;
}

OpenJfxMediaD3d11Meta* openjfx_media_d3d11_meta_add(
    GstBuffer* buffer,
    void* d3d11Texture,
    guint32 subresource,
    guint32 width, guint32 height)
{
    if (!buffer || !d3d11Texture) return NULL;
    const GstMetaInfo* info = openjfx_media_d3d11_meta_get_info();
    if (!info) return NULL;
    OpenJfxMediaD3d11Meta* m = (OpenJfxMediaD3d11Meta*)
        gst_buffer_add_meta(buffer, info, NULL);
    if (!m) return NULL;
#ifdef _WIN32
    ((IUnknown*)d3d11Texture)->AddRef();
#endif
    m->d3d11Texture = d3d11Texture;
    m->subresource  = subresource;
    m->width        = width;
    m->height       = height;
    m->kind         = OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_D3D11;
    return m;
}

OpenJfxMediaD3d11Meta* openjfx_media_d3d11_meta_get(GstBuffer* buffer)
{
    if (!buffer) return NULL;
    GType type = openjfx_media_d3d11_meta_api_get_type();
    if (type == 0) return NULL;
    return (OpenJfxMediaD3d11Meta*) gst_buffer_get_meta(buffer, type);
}

} // extern "C"
