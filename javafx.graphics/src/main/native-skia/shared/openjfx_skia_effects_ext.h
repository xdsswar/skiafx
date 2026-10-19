/*
 * Copyright (c) openjfx-skia contributors. Licensed under GPL-2.0+CE.
 *
 * Effect-peer extensions for the Skia bridge: SkRuntimeEffect-based image
 * filters that reproduce upstream Decora shader semantics which have no
 * single SkImageFilters primitive (DisplacementMap.jsl, PhongLighting.jsl).
 *
 * Handles returned here are ordinary bridge image / filter handles: release
 * them with openjfx_skia_image_destroy / openjfx_skia_filter_destroy and pass
 * filters to openjfx_skia_surface_save_layer_with_filter. Every entry point
 * validates its inputs and returns 0 on failure — never aborts or throws.
 */
#ifndef OPENJFX_SKIA_EFFECTS_EXT_H
#define OPENJFX_SKIA_EFFECTS_EXT_H

#include "openjfx_skia_bridge.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Uploads a FloatMap (w*h*4 floats, band-interleaved) as an RGBA F16 (signed, unclamped) raw
 * (non-colour) image. Non-finite samples are replaced by 0. w/h must be in
 * [1, 4096]. Returns an image handle or 0.
 */
OPENJFX_API uintptr_t openjfx_skia_fx_floatmap_image(
    const float* data, int32_t width, int32_t height);

/**
 * DisplacementMap.jsl as an image filter over the layer source.
 * params (count >= 9):
 *   [0..3] srcX, srcY, srcW, srcH  — source content rect in layer coords
 *   [4..5] offsetX, offsetY         — DisplacementMap offset (map units)
 *   [6..7] scaleX, scaleY           — DisplacementMap scale
 *   [8]    wrap (0 or 1)
 * The map (from openjfx_skia_fx_floatmap_image) is stretched over the source
 * rect. Returns a filter handle or 0.
 */
OPENJFX_API uintptr_t openjfx_skia_fx_displacement_filter(
    uintptr_t mapImage, const float* params, int32_t count);

/**
 * PhongLighting.jsl as an image filter. The layer source is the CONTENT
 * input (orig); bumpImage supplies the bump map (its alpha channel).
 * params (count >= 21):
 *   [0..3]   bumpX, bumpY, bumpW, bumpH  — bump content rect in layer coords
 *   [4]      surfaceScale
 *   [5]      diffuseConstant
 *   [6]      specularConstant
 *   [7]      specularExponent
 *   [8]      lightType (0 distant, 1 point, 2 spot)
 *   [9..11]  light colour, premultiplied RGB
 *   [12..14] distant: normalized light position; point/spot: light position
 *            in layer pixel coords
 *   [15..17] spot: normalized light direction
 *   [18]     spot: specular exponent
 *   [19..20] reserved (0)
 * Returns a filter handle or 0.
 */
OPENJFX_API uintptr_t openjfx_skia_fx_phong_filter(
    uintptr_t bumpImage, const float* params, int32_t count);

#ifdef __cplusplus
}
#endif

#endif /* OPENJFX_SKIA_EFFECTS_EXT_H */
