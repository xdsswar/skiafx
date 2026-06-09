/*
 * Copyright (c) 2010, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#ifndef Jfxmedia_jni_MediaTypes_h
#define Jfxmedia_jni_MediaTypes_h

//
// Supported media MIME types
//

#define CONTENT_TYPE_AIFF   "audio/x-aiff"
#define CONTENT_TYPE_MP3    "audio/mp3"
#define CONTENT_TYPE_MPA    "audio/mpeg"
#define CONTENT_TYPE_WAV    "audio/x-wav"
#define CONTENT_TYPE_MP4    "video/mp4"
#define CONTENT_TYPE_M4A    "audio/x-m4a"
#define CONTENT_TYPE_M4V    "video/x-m4v"
#define CONTENT_TYPE_M3U8   "application/vnd.apple.mpegurl"
#define CONTENT_TYPE_M3U    "audio/mpegurl"
#define CONTENT_TYPE_MP2T   "video/MP2T"
#define CONTENT_TYPE_FMP4   "video/quicktime"
#define CONTENT_TYPE_AAC    "audio/aac"

// skia-fx: matroska + webm containers. Routed to gstreamer's
// matroskademux (fetched at build time by skiafx.matroska-conventions).
// Both share the same demuxer; we keep two strings so the engine can
// distinguish webm (constrained codec set, vp8/vp9/av1 + opus/vorbis)
// from full matroska (any codec) in diagnostics. Pipeline routing is
// identical for both.
#define CONTENT_TYPE_MATROSKA "video/x-matroska"
#define CONTENT_TYPE_WEBM     "video/webm"

#endif
