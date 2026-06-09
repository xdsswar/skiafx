/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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
package javafx.scene.media;

import com.sun.javafx.geom.RectBounds;
import com.sun.javafx.media.PrismMediaFrameHandler;
import com.sun.javafx.sg.prism.MediaFrameTracker;
import com.sun.javafx.sg.prism.NGNode;
import com.sun.media.jfxmedia.control.VideoDataBuffer;
import com.sun.prism.Graphics;
import com.sun.prism.Texture;

/**
 */
class NGMediaView extends NGNode {

    private boolean smooth = true;
    private final RectBounds dimension = new RectBounds();
    private final RectBounds viewport = new RectBounds();
    private PrismMediaFrameHandler handler;
    private MediaPlayer player;
    private MediaFrameTracker frameTracker;

    public void renderNextFrame() {
        visualsChanged();
    }

    public boolean isSmooth() {
        return smooth;
    }

    public void setSmooth(boolean smooth) {
        if (smooth != this.smooth) {
            this.smooth = smooth;
            visualsChanged();
        }
    }

    public void setX(float x) {
        if (x != this.dimension.getMinX()) {
            float width = this.dimension.getWidth();
            this.dimension.setMinX(x);
            this.dimension.setMaxX(x + width);
            geometryChanged();
        }
    }

    public void setY(float y) {
        if (y != this.dimension.getMinY()) {
            float height = this.dimension.getHeight();
            this.dimension.setMinY(y);
            this.dimension.setMaxY(y + height);
            geometryChanged();
        }
    }

    public void setMediaProvider(Object provider) {
        if (provider == null) {
            player = null;
            handler = null;
            geometryChanged();
        } else if (provider instanceof MediaPlayer) {
            player = (MediaPlayer)provider;
            handler = PrismMediaFrameHandler.getHandler(player);
            geometryChanged();
        }
    }

    public void setViewport(float fitWidth, float fitHeight,
                            float vx, float vy, float vw, float vh,
                            boolean preserveRatio)
    {
        float w = 0;
        float h = 0;
        float newW = fitWidth;
        float newH = fitHeight;

        // determine media width/height
        if (null != player) {
            Media m = player.getMedia();
            w = m.getWidth();
            h = m.getHeight();
        }

        if (vw > 0 && vh > 0) {
            viewport.setBounds(vx, vy, vx+vw, vy+vh);
            w = vw;
            h = vh;
        } else {
            viewport.setBounds(0f, 0f, w, h);
        }
        if (fitWidth <= 0f && fitHeight <= 0f) {
            newW = w;
            newH = h;
        } else if (preserveRatio) {
            // FIXME: we should get the aspect ratio from the Media itself instead of assuming
            // (JDK-8092177)
            if (fitWidth <= 0.0) {
                newW = h > 0 ? w * (fitHeight / h) : 0.0f;
                newH = fitHeight;
            } else if (fitHeight <= 0.0) {
                newW = fitWidth;
                newH = w > 0 ? h * (fitWidth / w) : 0.0f;
            } else {
                if (w == 0.0f) w = fitWidth;
                if (h == 0.0f) h = fitHeight;
                float scale = Math.min(fitWidth / w, fitHeight / h);
                newW = w * scale;
                newH = h * scale;
            }
        } else if (fitHeight <= 0.0) {
            newH = h;
        } else if (fitWidth <= 0.0) {
            newW = w;
        }
        if (newH < 1f) {
            newH = 1f;
        }
        if (newW < 1f) {
            newW = 1f;
        }
        dimension.setMaxX(dimension.getMinX() + newW);
        dimension.setMaxY(dimension.getMinY() + newH);
        geometryChanged();
    }

    @Override
    protected void renderContent(Graphics g) {
        if (null == handler || null == player) {
            return; // not ready yet...
        }

        // Skip the render when the view's on-screen rect is below
        // 144p (or empty). At very small dims the downstream Skia GL
        // present path freezes — until that's fixed upstream, refusing
        // to render keeps the rest of the application responsive while
        // the user drags the window.
        if (dimension.isEmpty()
            || dimension.getWidth()  < 256
            || dimension.getHeight() < 144) {
            return;
        }

        VideoDataBuffer frame = player.getLatestFrame();
        if (null == frame) {
            return;
        }

        // Note: the producer's view-size hint channel (MediaTargetSize)
        // used to be published from here on every render pulse. With
        // the producer's output texture now pinned to source dimensions
        // (see ffmpegwrapper's pick_output_size), the hint is ignored
        // on the native side and publishing it was pure overhead on
        // the render thread — one JNI roundtrip per pulse per
        // MediaView. Skia scales the source-res texture to whatever
        // the on-screen dimension is at sample time on the GPU, so
        // no producer feedback is needed.

        Texture texture = handler.getTexture(g, frame);
        if (texture != null) {
            float iw = viewport.getWidth();
            float ih = viewport.getHeight();
            boolean dimensionsSet = !dimension.isEmpty();
            boolean doScale = dimensionsSet &&
                (iw != dimension.getWidth() || ih != dimension.getHeight());

            g.translate(dimension.getMinX(), dimension.getMinY());

            if (doScale && iw != 0 && ih != 0) {
                float scaleW = dimension.getWidth() / iw;
                float scaleH = dimension.getHeight() / ih;
                g.scale(scaleW, scaleH);
            }

            // Src rect must be in texture-pixel space. With HW-decoded
            // frames, the texture is potentially downscaled vs the
            // source viewport (e.g. 8K → 2K to match on-screen size);
            // viewport.getWidth() reports source-pixel dimensions, so
            // we ratio the crop origin and pull the extent from the
            // texture's actual content dims. For non-cropped frames
            // (the common case) sx1/sy1 are 0 and we just sample the
            // whole texture.
            float texW = texture.getContentWidth();
            float texH = texture.getContentHeight();
            float xRatio = texW / iw;
            float yRatio = texH / ih;
            float sx1 = viewport.getMinX() * xRatio;
            float sy1 = viewport.getMinY() * yRatio;
            float sx2 = sx1 + texW;
            float sy2 = sy1 + texH;

            g.drawTexture(texture,
                    0f, 0f, iw, ih,
                    sx1, sy1, sx2, sy2);
            texture.unlock();

            if (null != frameTracker) {
                frameTracker.incrementRenderedFrameCount(1);
            }
        }
        frame.releaseFrame();
    }

    @Override
    protected boolean hasOverlappingContents() {
        return false;
    }

    public void setFrameTracker(MediaFrameTracker t) {
        frameTracker = t;
    }
}
