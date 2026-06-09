// ---------------------------------------------------------------------------
// openjfx_media_d3d11_meta — custom GstMeta carrying a D3D11 texture handle.
//
// Producer (mfwrapper.cpp): attach the meta to a GstBuffer when the MFT
// decoded a frame into an RGBA D3D11 texture suitable for WGL interop.
// Consumer (GstVideoFrame.cpp in jfxmedia): on Init, look for the meta;
// if present, expose the texture pointer through CVideoFrame so the
// Java side can drive a zero-copy upload via SkiaMediaTexture.
//
// The meta AddRefs the underlying ID3D11Texture2D at attach time and
// Releases it in the meta destructor. The texture therefore survives
// for as long as any GstBuffer carrying this meta is alive, and is
// freed automatically when GStreamer disposes the buffer.
//
// Implementation lives in openjfx_media_d3d11_meta.cpp, which is added
// to BOTH the fxplugins target (so mfwrapper can attach) and the
// jfxmedia target (so GstVideoFrame can read). Each library compiles
// its own copy of the functions, but the underlying GStreamer registry
// is process-wide and idempotently keyed by API name, so both copies
// resolve to the same GstMetaInfo* at runtime.
// ---------------------------------------------------------------------------
#ifndef OPENJFX_MEDIA_D3D11_META_H
#define OPENJFX_MEDIA_D3D11_META_H

#include <gst/gst.h>

#ifdef __cplusplus
extern "C" {
#endif

// Mirrors com.sun.prism.MediaFrame.PLATFORM_TEXTURE_KIND_*.
#define OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_NONE      0
#define OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_D3D11     1
#define OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_IOSURFACE 2
#define OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_DMABUF    3

typedef struct _OpenJfxMediaD3d11Meta {
    GstMeta   meta;
    void*     d3d11Texture;   // ID3D11Texture2D* (AddRef'd at attach)
    guint32   subresource;    // index within a Texture2DArray; 0 for plain Tex2D
    guint32   width;          // pixel dimensions
    guint32   height;
    gint32    kind;           // OPENJFX_MEDIA_PLATFORM_TEXTURE_KIND_*
} OpenJfxMediaD3d11Meta;

GType                openjfx_media_d3d11_meta_api_get_type(void);
const GstMetaInfo*   openjfx_media_d3d11_meta_get_info(void);

// Attach the meta to `buffer`, AddRef'ing the texture. Returns NULL on failure.
OpenJfxMediaD3d11Meta* openjfx_media_d3d11_meta_add(
    GstBuffer* buffer,
    void* d3d11Texture,
    guint32 subresource,
    guint32 width, guint32 height);

// Lookup helper; returns NULL when no such meta is attached.
OpenJfxMediaD3d11Meta* openjfx_media_d3d11_meta_get(GstBuffer* buffer);

#ifdef __cplusplus
}
#endif

#endif // OPENJFX_MEDIA_D3D11_META_H
