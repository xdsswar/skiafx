// Internal (not exported) handle layouts shared by the native Skia bridge
// translation units (openjfx_skia_bridge.cpp, openjfx_skia_effects_ext.cpp).
//
// Each image / filter / shader handle crossing the FFM boundary is a heap
// struct fronted by a distinct magic sentinel, so a stale-but-still-mapped
// handle is rejected by the accessor instead of being blindly
// reinterpret_cast and dereferenced. The magic is zeroed at free time.
// One definition here keeps every TU's view of the layout identical: a
// handle made in one file is accepted and freed by the other.
#pragma once

#include <cstdint>
#include <utility>

#include "include/core/SkImage.h"
#include "include/core/SkImageFilter.h"
#include "include/core/SkRefCnt.h"
#include "include/core/SkShader.h"

namespace openjfx_handles {

struct ImageHandle;
// Live-image registry (defined in openjfx_skia_bridge.cpp, thread-safe). Lets
// device-loss recovery drop every texture-backed SkImage, which pins the old
// GrDirectContext — and through it the removed D3D12 device.
void trackImageHandle(ImageHandle* h);
void untrackImageHandle(ImageHandle* h);

struct ImageHandle {
    static constexpr uint32_t kMagic = 0x534B494Du; // 'SKIM'
    uint32_t       magic = kMagic;
    sk_sp<SkImage> sp;

    ImageHandle(uint32_t m, sk_sp<SkImage> s) : magic(m), sp(std::move(s)) {
        trackImageHandle(this);
    }
    ~ImageHandle() { untrackImageHandle(this); }
    ImageHandle(const ImageHandle&) = delete;
    ImageHandle& operator=(const ImageHandle&) = delete;
};
struct FilterHandle {
    static constexpr uint32_t kMagic = 0x534B4946u; // 'SKIF'
    uint32_t             magic = kMagic;
    sk_sp<SkImageFilter> sp;
};
struct ShaderHandle {
    static constexpr uint32_t kMagic = 0x534B5348u; // 'SKSH'
    uint32_t        magic = kMagic;
    sk_sp<SkShader> sp;
};

} // namespace openjfx_handles
