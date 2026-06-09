/*
 * Copyright (c) 2010, 2024, Oracle and/or its affiliates. All rights reserved.
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

#include <com_sun_media_jfxmediaimpl_platform_gstreamer_GSTMedia.h>
#include <com_sun_media_jfxmedia_control_VideoFormat_FormatTypes.h>

#include <Common/ProductFlags.h>
#include <Common/VSMemory.h>
#include <MediaManagement/Media.h>
#include <MediaManagement/MediaManager.h>
#include <PipelineManagement/PipelineOptions.h>
#include <PipelineManagement/VideoFrame.h>
#include <Locator/Locator.h>
#include <Locator/LocatorStream.h>
#include <jni/JniUtils.h>
#include <jni/JavaInputStreamCallbacks.h>
#include <jfxmedia_errors.h>
#include <Utils/LowLevelPerf.h>

using namespace std;

#define HLS_PROP_HAS_AUDIO_EXT_STREAM 6

//*************************************************************************************************
//********** com.sun.media.jfxmediaimpl.Media JNI support functions
//*************************************************************************************************

#ifdef __cplusplus
extern "C" {
#endif

    static jint InitMedia(JNIEnv *env, CPipelineOptions* pOptions, jobject jLocator, jstring jContentType, jlong jSizeHint,
                          jlongArray jlMediaHandle)
    {
        CMedia*         pMedia = NULL;
        char*           pjContent = (char*)env->GetStringUTFChars(jContentType , NULL);
        jstring         jLocation = CLocator::LocatorGetStringLocation(env, jLocator);
        char*           pjLocation = NULL;
        CMediaManager*  pManager = NULL;
        uint32_t        uErrCode = CMediaManager::GetInstance(&pManager);

        if (ERROR_NONE != uErrCode)
            return uErrCode;

        //***** pre-conditions
        if (NULL == pjContent)
        {
            return ERROR_MEMORY_ALLOCATION;
        }
        if (NULL == jLocation)
        {
            return ERROR_MEMORY_ALLOCATION;
        }
        pjLocation = (char*)env->GetStringUTFChars(jLocation , NULL);
        if (NULL == pjLocation)
        {
            env->ReleaseStringUTFChars(jContentType, pjContent);
            return ERROR_MEMORY_ALLOCATION;
        }
        if (NULL == pManager)
        {
            env->ReleaseStringUTFChars(jContentType, pjContent);
            env->ReleaseStringUTFChars(jLocation, pjLocation);
            return ERROR_MANAGER_NULL;
        }

        //***** Create a new native locator object
        CJavaInputStreamCallbacks *callbacks = new (nothrow) CJavaInputStreamCallbacks();
        jobject jConnectionHolder = CLocator::CreateConnectionHolder(env, jLocator);
        if (NULL == callbacks || NULL == jConnectionHolder)
            return ERROR_MEMORY_ALLOCATION;

        if (!callbacks->Init(env, jConnectionHolder))
        {
            env->ReleaseStringUTFChars(jContentType, pjContent);
            env->ReleaseStringUTFChars(jLocation, pjLocation);
            delete callbacks;
            return ERROR_MEDIA_CREATION;
        }

        CLocatorStream *locator = new(nothrow) CLocatorStream(callbacks, pjContent, pjLocation, (int64_t)jSizeHint);
        env->ReleaseStringUTFChars(jContentType, pjContent);
        env->ReleaseStringUTFChars(jLocation, pjLocation);

        if (NULL == locator)
        {
            delete callbacks;
            return ERROR_MEMORY_ALLOCATION;
        }

        // Skia-fx: lift the optional companion audio URL from the
        // Java Locator and store it on the C++ side. When non-empty,
        // GstAVPlaybackPipeline builds a second source bin into the
        // same GstPipeline so GStreamer's shared clock can sync the
        // two sources at the sample level.
        jstring jCompanionUrl = CLocator::GetCompanionAudioUrl(env, jLocator);
        if (jCompanionUrl != NULL)
        {
            const char* pjCompanion = env->GetStringUTFChars(jCompanionUrl, NULL);
            if (pjCompanion != NULL)
            {
                if (pjCompanion[0] != '\0')
                {
                    string companion(pjCompanion);
                    // skia-fx: http(s) companions stream through the same
                    // javasource Java-I/O bridge the primary source uses
                    // (souphttpsrc isn't in the gstreamer-lite build).
                    // file:// companions keep the lighter native filesrc
                    // path (CreateCompanionAudioSource).
                    bool isHttp =
                        (companion.compare(0, 7, "http://") == 0) ||
                        (companion.compare(0, 8, "https://") == 0);
                    bool routedThroughBridge = false;
                    if (isHttp)
                    {
                        jobject jCompanionHolder =
                            CLocator::GetCompanionAudioConnectionHolder(env, jLocator);
                        if (jCompanionHolder != NULL)
                        {
                            CJavaInputStreamCallbacks *companionCallbacks =
                                new (nothrow) CJavaInputStreamCallbacks();
                            if (companionCallbacks != NULL &&
                                companionCallbacks->Init(env, jCompanionHolder))
                            {
                                // Ownership transfers to the audio source
                                // element; freed via SourceCloseConnection
                                // on pipeline dispose (same as the primary
                                // and HLS audio-ext callbacks).
                                locator->SetAudioCallbacks(companionCallbacks);

                                jstring jCompanionType =
                                    CLocator::GetCompanionAudioContentType(env, jLocator);
                                if (jCompanionType != NULL)
                                {
                                    const char* pjType =
                                        env->GetStringUTFChars(jCompanionType, NULL);
                                    if (pjType != NULL)
                                    {
                                        locator->SetCompanionAudioContentType(string(pjType));
                                        env->ReleaseStringUTFChars(jCompanionType, pjType);
                                    }
                                    env->DeleteLocalRef(jCompanionType);
                                }
                                routedThroughBridge = true;
                            }
                            else
                            {
                                // Init failed — drop the half-built
                                // callbacks; fall through to filesrc which
                                // surfaces a clear error for http.
                                delete companionCallbacks;
                            }
                            env->DeleteLocalRef(jCompanionHolder);
                        }
                    }
                    if (!routedThroughBridge)
                    {
                        // file:// companion (or http bridge unavailable) →
                        // native source path.
                        locator->SetCompanionAudioLocation(companion);
                    }
                }
                env->ReleaseStringUTFChars(jCompanionUrl, pjCompanion);
            }
            env->DeleteLocalRef(jCompanionUrl);
        }

        // Load any additional streams if needed.
        // HLS_PROP_HAS_AUDIO_EXT_STREAM
        int hasAudioStream = callbacks->Property(HLS_PROP_HAS_AUDIO_EXT_STREAM, 0);
        if (hasAudioStream)
        {
            CJavaInputStreamCallbacks *audioStreamCallbacks =
                    new (nothrow) CJavaInputStreamCallbacks();
            jobject jAudioStreamConnectionHolder =
                    CLocator::GetAudioStreamConnectionHolder(env, jLocator, jConnectionHolder);
            if (NULL == audioStreamCallbacks || NULL == jAudioStreamConnectionHolder)
            {
                delete callbacks;
                delete locator;
                return ERROR_MEMORY_ALLOCATION;
            }

            if (!audioStreamCallbacks->Init(env, jAudioStreamConnectionHolder))
            {
                delete callbacks;
                delete audioStreamCallbacks;
                delete locator;
                return ERROR_MEDIA_CREATION;
            }

            locator->SetAudioCallbacks(audioStreamCallbacks);
        }

        //***** Create the media object
        uErrCode  = pManager->CreatePlayer(locator, pOptions, &pMedia);

        //***** return
        if (ERROR_NONE == uErrCode)
        {
            if (CMedia::IsValid(pMedia))
            {
                jlong lMediaHandle = (jlong)ptr_to_jlong(pMedia);
                env->SetLongArrayRegion(jlMediaHandle, 0, 1, &lMediaHandle);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
            else
            {
                uErrCode = ERROR_MEDIA_INVALID;
            }
        }

        // Free locator
        if (locator != NULL)
            delete locator;

        // Clean up
        if (ERROR_NONE != uErrCode)
        {
            if (NULL != pMedia)
                delete pMedia;
        }

        return uErrCode;
    }

    /**
     * GSTMedia_gstInitNativeMedia()
     *
     * Creates a native media reference for the resource string.
     *
     * @return  Media reference.  This reference must be used when calling GSTMediaPlayer function.
     */
    JNIEXPORT jint JNICALL Java_com_sun_media_jfxmediaimpl_platform_gstreamer_GSTMedia_gstInitNativeMedia
    (JNIEnv *env, jobject obj, jobject jLocator, jstring jContentType, jlong jSizeHint, jlongArray jlMediaHandle)
    {
        LOWLEVELPERF_EXECTIMESTART("gstInitNativeMediaToSendToJavaPlayerStateEventPaused");
        LOWLEVELPERF_EXECTIMESTART("gstInitNativeMedia()");
        uint32_t result = InitMedia(env, NULL, jLocator, jContentType, jSizeHint, jlMediaHandle);
        LOWLEVELPERF_EXECTIMESTOP("gstInitNativeMedia()");

        return result;
    }

    /*
    * Class:     com_sun_media_jfxmediaimpl_platform_gstreamer_GSTMedia
    * Method:    gstDispose
    * Signature: (J)V
    */
    JNIEXPORT void JNICALL Java_com_sun_media_jfxmediaimpl_platform_gstreamer_GSTMedia_gstDispose
    (JNIEnv *env, jobject obj, jlong ref_media)
    {
        LOWLEVELPERF_EXECTIMESTART("gstDispose()");

        CMedia* pMedia = (CMedia*)jlong_to_ptr(ref_media);

        if (pMedia != NULL)
        {
            delete pMedia;
            pMedia = NULL;
        }

        LOWLEVELPERF_EXECTIMESTOP("gstDispose()");
    }

#ifdef __cplusplus
}
#endif
