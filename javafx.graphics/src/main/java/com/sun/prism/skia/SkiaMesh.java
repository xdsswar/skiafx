package com.sun.prism.skia;

import com.sun.prism.impl.BaseMesh;
import com.sun.prism.impl.Disposer;
import com.sun.prism.skia.impl.NativeBridge3D;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skia/bgfx-backed {@link com.sun.prism.Mesh}.
 *
 * <p>Extends {@link BaseMesh} so we reuse upstream geometry compilation
 * (normals, tangents, dedup, normal→quaternion). {@code BaseMesh} hands
 * us the interleaved vertex buffer (9 floats/vertex: pos.xyz, texCoord.uv,
 * normal-as-quat.xyzw) plus an index buffer; we only upload it to the
 * native bgfx renderer. Mirrors {@code ES2Mesh}. See docs/3D.md.</p>
 */
final class SkiaMesh extends BaseMesh {

    private static final AtomicInteger count = new AtomicInteger();

    private final long nativeHandle;
    private boolean countReleased;

    private SkiaMesh(long nativeHandle, Disposer.Record disposerRecord) {
        super(disposerRecord);
        this.nativeHandle = nativeHandle;
        count.incrementAndGet();
    }

    static SkiaMesh create() {
        long h = NativeBridge3D.meshCreate();
        return new SkiaMesh(h, new SkiaMeshDisposerRecord(h));
    }

    long getNativeHandle() { return nativeHandle; }

    @Override
    public boolean buildNativeGeometry(float[] vertexBuffer, int vertexBufferLength,
                                       int[] indexBuffer, int indexBufferLength) {
        return NativeBridge3D.meshBuildInt(nativeHandle,
                vertexBuffer, vertexBufferLength, indexBuffer, indexBufferLength);
    }

    @Override
    public boolean buildNativeGeometry(float[] vertexBuffer, int vertexBufferLength,
                                       short[] indexBuffer, int indexBufferLength) {
        return NativeBridge3D.meshBuildShort(nativeHandle,
                vertexBuffer, vertexBufferLength, indexBuffer, indexBufferLength);
    }

    @Override
    public int getCount() {
        return count.get();
    }

    @Override
    public void dispose() {
        // Idempotent: the disposer record already guards its native free, but the
        // live-mesh counter must be decremented exactly once even if dispose() is
        // called more than once (otherwise the stat drifts negative).
        disposerRecord.dispose();
        if (!countReleased) {
            countReleased = true;
            count.decrementAndGet();
        }
    }

    static final class SkiaMeshDisposerRecord implements Disposer.Record {
        private long handle;
        SkiaMeshDisposerRecord(long handle) { this.handle = handle; }
        @Override public void dispose() {
            if (handle != 0L) {
                NativeBridge3D.meshDestroy(handle);
                handle = 0L;
            }
        }
    }
}
