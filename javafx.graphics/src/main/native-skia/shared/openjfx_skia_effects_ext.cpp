/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 *
 * SkRuntimeEffect-based image filters for effect peers whose upstream
 * Decora shader has no 1:1 SkImageFilters primitive. See the header for the
 * parameter contracts. Separate TU so effect work does not touch the main
 * bridge; handles interoperate with the bridge's image/filter handles.
 */
#include "openjfx_skia_effects_ext.h"

#include <cmath>
#include <cstring>
#include <cstddef>

#ifdef OPENJFX_WITH_SKIA
  #include "include/core/SkData.h"
  #include "include/core/SkImage.h"
  #include "include/core/SkImageFilter.h"
  #include "include/core/SkImageInfo.h"
  #include "include/core/SkM44.h"
  #include "include/core/SkMatrix.h"
  #include "include/core/SkRefCnt.h"
  #include "include/core/SkSamplingOptions.h"
  #include "include/core/SkShader.h"
  #include "include/core/SkString.h"
  #include "include/core/SkTileMode.h"
  #include "include/effects/SkImageFilters.h"
  #include "include/effects/SkRuntimeEffect.h"
  #include "src/base/SkHalf.h"
  #include <string_view>
#endif

#ifdef OPENJFX_WITH_SKIA
#include "openjfx_skia_handles.h"

namespace {

// Handle interop: the same magic-fronted handle structs as the bridge
// (openjfx_skia_handles.h), so handles created here are accepted and freed
// by the bridge's image/filter entry points, and vice versa.
using FxImageHandle  = openjfx_handles::ImageHandle;
using FxFilterHandle = openjfx_handles::FilterHandle;

uintptr_t makeImage(sk_sp<SkImage> img) {
    if (!img) return 0;
    return reinterpret_cast<uintptr_t>(new FxImageHandle{FxImageHandle::kMagic, std::move(img)});
}
uintptr_t makeFilter(sk_sp<SkImageFilter> f) {
    if (!f) return 0;
    return reinterpret_cast<uintptr_t>(new FxFilterHandle{FxFilterHandle::kMagic, std::move(f)});
}
SkImage* asImage(uintptr_t handle) {
    auto* h = reinterpret_cast<FxImageHandle*>(handle);
    if (!h || h->magic != FxImageHandle::kMagic) return nullptr;
    return h->sp.get();
}

bool allFinite(const float* p, int32_t n) {
    for (int32_t i = 0; i < n; i++) {
        if (!std::isfinite(p[i])) return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// DisplacementMap.jsl
//   off  = sample(mapImg, pos1)
//   loc  = pos0 + scale * (off.xy + offset)
//   loc -= wrap * floor(loc)
//   loc  = inset + loc * (size - 2*inset)     (inset = wrap ? 0.5px : 0)
//   color = sample(origImg, loc)
// pos0/pos1 are unit coordinates over the source content; the map is
// stretched over it.
// ---------------------------------------------------------------------------
const char* kDisplacementSkSL = R"(
uniform shader src;
uniform shader map;
uniform float4 srcRect;   // x, y, w, h in layer coords
uniform float2 mapSize;
uniform float4 sampletx;  // offsetX, offsetY, scaleX, scaleY
uniform float  wrap;

half4 main(float2 p) {
    float2 u = (p - srcRect.xy) / srcRect.zw;
    if (u.x < 0.0 || u.y < 0.0 || u.x >= 1.0 || u.y >= 1.0) {
        return half4(0);
    }
    float4 off = float4(map.eval(u * mapSize));
    float2 loc = u + sampletx.zw * (off.xy + sampletx.xy);
    loc -= wrap * floor(loc);
    float inset = wrap * 0.5;
    float2 px = inset + loc * (srcRect.zw - 2.0 * inset);
    // Non-wrapped lookups outside the source are transparent (clamp-to-zero).
    if (px.x < 0.0 || px.y < 0.0 || px.x > srcRect.z || px.y > srcRect.w) {
        return half4(0);
    }
    return src.eval(srcRect.xy + px);
}
)";

// ---------------------------------------------------------------------------
// PhongLighting.jsl (+ CompilePhong light variants), evaluated per layer
// pixel. pixcoord == the layer (destination) pixel centre, exactly as the
// Decora peers see it.
// ---------------------------------------------------------------------------
const char* kPhongSkSL = R"(
uniform shader orig;
uniform shader bump;
uniform float4 bumpRect;     // x, y, w, h in layer coords
uniform float  surfaceScale;
uniform float  diffuseConstant;
uniform float  specularConstant;
uniform float  specularExponent;
uniform float  lightType;    // 0 distant, 1 point, 2 spot
uniform float3 lightColor;   // premultiplied rgb
uniform float3 lightPos;     // distant: normalized position; else position
uniform float3 lightDir;     // spot: normalized direction
uniform float  lightSpecExp; // spot exponent

float bumpA(float2 q) {
    if (q.x < bumpRect.x || q.y < bumpRect.y ||
        q.x >= bumpRect.x + bumpRect.z || q.y >= bumpRect.y + bumpRect.w) {
        return 0.0;
    }
    return float(bump.eval(q).a);
}

half4 main(float2 p) {
    float4 o = float4(orig.eval(p));

    // Sobel normal, factor = -surfaceScale / 4 (PhongLighting.jsl getKvals).
    float f = -surfaceScale * 0.25;
    float a00 = bumpA(p + float2(-1, -1));
    float a01 = bumpA(p + float2( 0, -1));
    float a02 = bumpA(p + float2( 1, -1));
    float a10 = bumpA(p + float2(-1,  0));
    float a12 = bumpA(p + float2( 1,  0));
    float a20 = bumpA(p + float2(-1,  1));
    float a21 = bumpA(p + float2( 0,  1));
    float a22 = bumpA(p + float2( 1,  1));
    float sx = (-a00 + a02 - 2.0 * a10 + 2.0 * a12 - a20 + a22) * f;
    float sy = (-a00 - 2.0 * a01 - a02 + a20 + 2.0 * a21 + a22) * f;
    float3 N = normalize(float3(sx, sy, 1.0));

    float3 L;
    float3 Lrgb = lightColor;
    if (lightType < 0.5) {
        L = lightPos;
    } else {
        float3 tmp = float3(p.x, p.y, surfaceScale * bumpA(p));
        L = normalize(lightPos - tmp);
        if (lightType > 1.5) {
            float LdotS = min(dot(L, lightDir), 0.0);
            Lrgb = lightColor * pow(-LdotS, lightSpecExp);
        }
    }

    float3 E = float3(0.0, 0.0, 1.0);
    float3 H = normalize(L + E);

    float4 D;
    D.rgb = clamp(diffuseConstant * dot(N, L) * Lrgb, 0.0, 1.0);
    D.a = 1.0;

    float4 S;
    float NdotH = max(dot(N, H), 0.0);
    S.rgb = specularConstant * pow(NdotH, specularExponent) * Lrgb;
    S.a = max(max(S.r, S.g), S.b);

    o *= D;
    S *= o.a;
    float4 c = S + o * (1.0 - S.a);
    c = clamp(c, 0.0, 1.0);
    c.rgb = min(c.rgb, c.aaa);
    return half4(c);
}
)";

sk_sp<SkRuntimeEffect> compile(const char* sksl) {
    auto result = SkRuntimeEffect::MakeForShader(SkString(sksl));
    return result.effect; // null on compile failure — callers return 0
}

SkRuntimeEffect* displacementEffect() {
    static sk_sp<SkRuntimeEffect> effect = compile(kDisplacementSkSL);
    return effect.get();
}

SkRuntimeEffect* phongEffect() {
    static sk_sp<SkRuntimeEffect> effect = compile(kPhongSkSL);
    return effect.get();
}

} // namespace
#endif // OPENJFX_WITH_SKIA

OPENJFX_API uintptr_t openjfx_skia_fx_floatmap_image(
    const float* data, int32_t width, int32_t height) {
#ifdef OPENJFX_WITH_SKIA
    if (!data || width <= 0 || height <= 0 || width > 4096 || height > 4096) {
        return 0;
    }
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    // Half-float (kRGBA_F16, NOT the clamped F16Norm): signed and unclamped,
    // ~1e-3 relative precision, and texturable on every Ganesh backend.
    // RGBA_F32 is not texturable on D3D12, where Skia's upload fallback
    // clamped negative displacements to 0.
    sk_sp<SkData> pixels = SkData::MakeUninitialized(count * sizeof(SkHalf));
    if (!pixels) return 0;
    SkHalf* dst = static_cast<SkHalf*>(pixels->writable_data());
    for (size_t i = 0; i < count; i++) {
        float v = data[i];
        if (!std::isfinite(v)) v = 0.0f;
        if (v >  65504.0f) v =  65504.0f;
        if (v < -65504.0f) v = -65504.0f;
        dst[i] = SkFloatToHalf(v);
    }
    // Unpremul + no colour space + raw shader at sample time: the samples
    // reach the runtime effect untouched (no premul, no colour conversion).
    SkImageInfo info = SkImageInfo::Make(width, height,
                                         kRGBA_F16_SkColorType,
                                         kUnpremul_SkAlphaType);
    sk_sp<SkImage> img = SkImages::RasterFromData(
        info, std::move(pixels), static_cast<size_t>(width) * 4 * sizeof(SkHalf));
    return makeImage(std::move(img));
#else
    (void)data; (void)width; (void)height;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_fx_displacement_filter(
    uintptr_t mapImage, const float* params, int32_t count) {
#ifdef OPENJFX_WITH_SKIA
    if (!params || count < 9 || !allFinite(params, 9)) return 0;
    SkImage* map = asImage(mapImage);
    if (!map) return 0;
    const float srcW = params[2], srcH = params[3];
    if (!(srcW > 0.0f) || !(srcH > 0.0f)) return 0;
    SkRuntimeEffect* effect = displacementEffect();
    if (!effect) return 0;

    sk_sp<SkShader> mapShader = map->makeRawShader(
        SkTileMode::kClamp, SkTileMode::kClamp,
        SkSamplingOptions(SkFilterMode::kNearest));
    if (!mapShader) return 0;

    SkRuntimeEffectBuilder builder(sk_ref_sp(effect));
    builder.uniform("srcRect")  = SkV4{params[0], params[1], srcW, srcH};
    builder.uniform("mapSize")  = SkV2{static_cast<float>(map->width()),
                                       static_cast<float>(map->height())};
    builder.uniform("sampletx") = SkV4{params[4], params[5], params[6], params[7]};
    builder.uniform("wrap")     = params[8] != 0.0f ? 1.0f : 0.0f;
    builder.child("map")        = std::move(mapShader);

    // Any output pixel may sample anywhere in the source.
    const float radius = srcW > srcH ? srcW : srcH;
    sk_sp<SkImageFilter> f = SkImageFilters::RuntimeShader(
        builder, radius, "src", nullptr);
    return makeFilter(std::move(f));
#else
    (void)mapImage; (void)params; (void)count;
    return 0;
#endif
}

OPENJFX_API uintptr_t openjfx_skia_fx_phong_filter(
    uintptr_t bumpImage, const float* params, int32_t count) {
#ifdef OPENJFX_WITH_SKIA
    if (!params || count < 21 || !allFinite(params, 21)) return 0;
    SkImage* bump = asImage(bumpImage);
    if (!bump) return 0;
    if (!(params[2] > 0.0f) || !(params[3] > 0.0f)) return 0;
    SkRuntimeEffect* effect = phongEffect();
    if (!effect) return 0;

    // Bump pixel (0,0) sits at (bumpX, bumpY) in layer coords.
    SkMatrix local = SkMatrix::Translate(params[0], params[1]);
    sk_sp<SkShader> bumpShader = bump->makeShader(
        SkTileMode::kDecal, SkTileMode::kDecal,
        SkSamplingOptions(SkFilterMode::kNearest), &local);
    if (!bumpShader) return 0;

    SkRuntimeEffectBuilder builder(sk_ref_sp(effect));
    builder.uniform("bumpRect")         = SkV4{params[0], params[1], params[2], params[3]};
    builder.uniform("surfaceScale")     = params[4];
    builder.uniform("diffuseConstant")  = params[5];
    builder.uniform("specularConstant") = params[6];
    builder.uniform("specularExponent") = params[7];
    builder.uniform("lightType")        = params[8];
    builder.uniform("lightColor")       = SkV3{params[9],  params[10], params[11]};
    builder.uniform("lightPos")         = SkV3{params[12], params[13], params[14]};
    builder.uniform("lightDir")         = SkV3{params[15], params[16], params[17]};
    builder.uniform("lightSpecExp")     = params[18];
    builder.child("bump")               = std::move(bumpShader);

    // orig is only sampled at the output coordinate.
    sk_sp<SkImageFilter> f = SkImageFilters::RuntimeShader(
        builder, 0.0f, "orig", nullptr);
    return makeFilter(std::move(f));
#else
    (void)bumpImage; (void)params; (void)count;
    return 0;
#endif
}
