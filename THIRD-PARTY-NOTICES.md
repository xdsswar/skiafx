# Third-Party Notices

skia-fx itself is distributed under the **GPL v2 with the Classpath Exception**, the
same license as OpenJFX (see `LICENSE`). It also includes, links against, or loads at
runtime a number of third-party components, each under its own license. Those licenses
and the required attribution notices are reproduced below.

This file is a convenience summary. Components inherited unchanged from OpenJFX keep
their original per-module notices under each module's `src/main/legal/` directory; those
are authoritative and are listed in [section 3](#3-components-inherited-from-openjfx).

Sections:
1. [Native engines added by skia-fx](#1-native-engines-added-by-skia-fx) (bundled in binaries)
2. [Media codecs (ffmpeg) — loaded at runtime, not bundled](#2-media-codecs-ffmpeg--loaded-at-runtime-not-bundled)
3. [Components inherited from OpenJFX](#3-components-inherited-from-openjfx)

---

## 1. Native engines added by skia-fx

These are compiled into / linked with the skia-fx native libraries and are redistributed
in binary form. All are permissive (BSD/MIT) and impose no copyleft on applications built
with skia-fx.

### 1.1 Skia — BSD-3-Clause

Graphics engine behind the Skia renderer (2D, text, image, and SVG via `SkSVGDOM`).

```
Copyright (c) 2011 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

> Note: Skia itself vendors numerous third-party libraries (FreeType, libpng, libwebp,
> harfbuzz, etc.), each under its own (permissive) license. Their full notices ship with
> the Skia source tree (`$SKIA_HOME`) under `third_party/`.

### 1.2 bgfx (with bx and bimg) — BSD-2-Clause

Cross-platform rendering library powering the 3D scene graph (`javafx.scene3d`). The
companion libraries **bx** and **bimg** are by the same author under the same license.

```
Copyright 2010-2020 Branimir Karadzic

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

   1. Redistributions of source code must retain the above copyright notice, this
      list of conditions and the following disclaimer.

   2. Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
OF THE POSSIBILITY OF SUCH DAMAGE.
```

### 1.3 cgltf — MIT

Single-file glTF 2.0 parser used by the `javafx.scene3d` model loader. (bgfx also vendors
a copy of cgltf under `3rdparty/cgltf`, same license.)

```
cgltf is distributed under MIT license:

Copyright (c) 2018-2021 Johannes Kuhlmann

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 1.4 Chromium / Blink — BSD-3-Clause

The Blink engine powers the new `javafx.web` WebView (off-screen Chromium). Chromium is a
large project that embeds many third-party libraries; the umbrella Chromium license is
reproduced below, and the complete set of component notices is generated by Chromium's
`about:credits` and ships with the Chromium source tree.

```
Copyright 2015 The Chromium Authors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## 2. Media codecs (ffmpeg) — loaded at runtime, not bundled

`javafx.media` decodes through ffmpeg via a **runtime dynamic loader**: it `LoadLibrary`s
`avcodec-*`, `avformat-*`, `avutil-*` (and, for CPU AV1, `dav1d`) at startup. skia-fx is
built against ffmpeg's **public headers only** and **does not ship any ffmpeg binary**.

Consequences:

- ffmpeg's own license (**LGPL-2.1+**, or GPL depending on how a given build was
  configured) and the licenses of libraries it bundles (e.g. dav1d, BSD-2-Clause) attach
  to **whoever distributes the ffmpeg binaries** — not to skia-fx. If you bundle ffmpeg
  DLLs/`.so`/`.dylib` with your application, you must satisfy those terms yourself
  (notably the LGPL relinking/attribution obligations).
- **Codec patents** are independent of software license. Decoders/encoders for H.264,
  H.265/HEVC, AAC, etc. are covered by patent pools (Via LA / MPEG-LA). Distributing them
  commercially may require a separate patent license. This is your responsibility as the
  distributor and is not granted by any of the licenses in this file.

Decode strategy is selected at runtime via `Media.setDecodeMethod(...)` and
`Media.setFfmpegDirectory(...)`. See [`docs/DUAL_SOURCE_MEDIA.md`](docs/DUAL_SOURCE_MEDIA.md).

---

## 3. Components inherited from OpenJFX

The following third-party components are part of upstream OpenJFX and are unchanged by
skia-fx. Their authoritative notices live in each module's `src/main/legal/` directory:

| Component | License (summary) | Notice |
| --- | --- | --- |
| GStreamer (media fallback) | LGPL-2.1 (+ BSD parts) | `javafx.media/src/main/legal/gstreamer.md` |
| GLib | LGPL-2.1 | `javafx.media/src/main/legal/glib.md` |
| libffi | MIT-style | `javafx.media/src/main/legal/libffi.md` |
| DirectShow base classes | (see notice) | `javafx.media/src/main/legal/directshow.md` |
| Independent JPEG Group (libjpeg) | IJG | `javafx.graphics/src/main/legal/jpeg_fx.md` |
| Mesa 3D headers | MIT | `javafx.graphics/src/main/legal/mesa3d.md` |
| PipeWire headers | MIT | `javafx.graphics/src/main/legal/pipewire.md` |
| GCC runtime (libgcc) | GPL + runtime exception | `javafx.graphics/src/main/legal/gcc.md` |

The license-summary column is a convenience only; the linked notice file governs in each
case.

> The WebView no longer uses the WebKit C++ port — it is fully **Blink** (see §1.4). The
> legacy notice files still present under `javafx.web/src/main/legal/` (`webkit.md`,
> `libxml2.md`, `libxslt.md`, `icu_web.md`) cover that retired engine, which is **no longer
> built or shipped**. Blink vendors its own copies of libxml2, ICU, etc.; those are covered
> by Chromium's `about:credits`.

---

*If you redistribute skia-fx or an application built on it, ship this file (and, for any
ffmpeg binaries you choose to bundle, the corresponding ffmpeg notices) alongside your
binaries.*
