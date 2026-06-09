/*
 * Copyright (c) 2010, 2023, Oracle and/or its affiliates. All rights reserved.
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

#include "com_sun_media_jfxmedia_control_VideoDataBuffer.h"
#include "com_sun_media_jfxmedia_control_VideoFormat_FormatTypes.h"
#include "com_sun_media_jfxmediaimpl_NativeVideoBuffer.h"

#include <PipelineManagement/VideoFrame.h>
#include "JniUtils.h"

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeDisposeBuffer
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeDisposeBuffer
    (JNIEnv *env, jclass klass, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        delete frame;
    }
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetTimestamp
 * Signature: (J)D
 */
JNIEXPORT jdouble JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetTimestamp
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetTime();
    }
    return 0.0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetBuffer
 * Signature: (JI)Ljava/nio/ByteBuffer;
 *
 * WARNING: This method will create a new ByteBuffer object, you should cache this object to avoid multiple allocations.
 */
JNIEXPORT jobject JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetBufferForPlane
    (JNIEnv *env, jobject obj, jlong nativeHandle, jint plane)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        void *dataPtr = frame->GetDataForPlane((unsigned int)plane);
        jlong capacity = (jlong)frame->GetSizeForPlane((unsigned int)plane);
        jobject buffer = env->NewDirectByteBuffer(dataPtr, capacity);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return NULL;
        }
        return buffer;
    }
    return NULL;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetWidth
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetWidth
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetWidth();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetHeight
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetHeight
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetHeight();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetEncodedWidth
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetEncodedWidth
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetEncodedWidth();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetEncodedHeight
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetEncodedHeight
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetEncodedHeight();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetFormat
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetFormat
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        // CVideoFrame types now match Java VideoFormat native types, so just pass it along
        return (jint)frame->GetType();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeHasAlpha
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeHasAlpha
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->HasAlpha();
    }
    return JNI_FALSE;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetPlaneCount
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlaneCount
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return frame->GetPlaneCount();
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeGetPlaneStrides
 * Signature: (J)[I
 */
JNIEXPORT jintArray JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlaneStrides
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        jint count = (jint)frame->GetPlaneCount();
        // Sanity check plane count, never more than four or less than 1
        if (count > 4 || count < 1) {
            return NULL;
        }

        jintArray strides = env->NewIntArray(count);
        if (strides == NULL) {
            return NULL;
        }

        jint *strideArray = new (std::nothrow) jint[count];
        if (strideArray == NULL) {
            return NULL;
        }

        for (unsigned int ii=0; ii < count; ii++) {
            strideArray[ii] = frame->GetStrideForPlane(ii);
        }

        env->SetIntArrayRegion(strides, 0, count, strideArray);
        delete [] strideArray;

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return NULL;
        }

        return strides;
    }
    return NULL;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeConvertToFormat
 * Signature: (JI)J
 */
JNIEXPORT jlong JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeConvertToFormat
    (JNIEnv *env, jobject obj, jlong nativeHandle, jint newFormat)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        return ptr_to_jlong(frame->ConvertToFormat((CVideoFrame::FrameType)newFormat));
    }
    return 0;
}

/*
 * Class:     com_sun_media_jfxmediaimpl_NativeVideoBuffer
 * Method:    nativeSetDirty
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeSetDirty
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    if (frame) {
        frame->SetFrameDirty(true);
    }
}

/*
 * M3-B platform-GPU-texture surface.
 *
 * These three methods expose the optional GPU-resident texture that a
 * HW decoder may have attached to the frame. They return KIND_NONE / 0
 * for software frames, which keeps the rest of the VideoDataBuffer
 * interface honest (the plane-buffer path still works unchanged).
 *
 * Signatures aren't in com_sun_media_jfxmediaimpl_NativeVideoBuffer.h
 * yet — that header is generated from NativeVideoBuffer.java by javac
 * -h. The Java-side additions in this commit drive that regeneration.
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlatformTextureKind
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetPlatformTextureKind() : 0;
}

JNIEXPORT jlong JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlatformTextureHandle
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? ptr_to_jlong(frame->GetPlatformTextureHandle()) : 0;
}

JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlatformTextureSubresource
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetPlatformTextureSubresource() : 0;
}

JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlatformTextureWidth
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetPlatformTextureWidth() : 0;
}

JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetPlatformTextureHeight
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetPlatformTextureHeight() : 0;
}

/*
 * YUV colour-space hint, lifted from the demuxer's caps (GstCaps's
 * "colorimetry" field) by CGstVideoFrame::SetFrameCaps. Returns
 * CVideoFrame::YUV_COLORSPACE_AUTO (-1) when no caps-level hint is
 * available — the Java consumer then falls back to its own
 * resolution-based heuristic.
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetYuvColorSpace
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetYuvColorSpace() : -1;
}

/*
 * Transfer function lifted from caps. Returns
 * CVideoFrame::TRANSFER_AUTO (-1) when no caps-level hint exists;
 * the Java consumer combines with the resolution heuristic to
 * decide whether to engage the HDR tone-mapping path.
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetColorTransfer
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetColorTransfer() : -1;
}

/*
 * RGB primaries (Rec.709 / Rec.2020 / DCI-P3) lifted from caps, or
 * PRIMARIES_AUTO (-1) when absent.
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetColorPrimaries
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetColorPrimaries() : -1;
}

/*
 * YUV value range (limited / full) lifted from caps, or RANGE_AUTO
 * (-1) when absent.
 */
JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetColorRange
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jint)frame->GetColorRange() : -1;
}

/*
 * Mastering display peak luminance (nits). 0.0f when the file
 * didn't carry mastering metadata — consumer falls back to a
 * 1000-nit assumption for HDR content.
 */
JNIEXPORT jfloat JNICALL Java_com_sun_media_jfxmediaimpl_NativeVideoBuffer_nativeGetMasteringPeakNits
    (JNIEnv *env, jobject obj, jlong nativeHandle)
{
    CVideoFrame *frame = (CVideoFrame*)jlong_to_ptr(nativeHandle);
    return frame ? (jfloat)frame->GetMasteringPeakNits() : 0.f;
}
