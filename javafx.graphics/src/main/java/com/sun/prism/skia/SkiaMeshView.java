package com.sun.prism.skia;

import com.sun.javafx.geom.Vec3d;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.sg.prism.NGCamera;
import com.sun.prism.Graphics;
import com.sun.prism.Material;
import com.sun.prism.impl.BaseMeshView;
import com.sun.prism.impl.Disposer;
import com.sun.prism.skia.impl.NativeBridge3D;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skia/bgfx-backed {@link com.sun.prism.MeshView}.
 *
 * <p>Holds the mesh + material + per-light state in the native renderer
 * and, on {@link #render}, pulls the camera (proj×view) and the node's
 * world transform from the {@link Graphics}, builds the matrices bgfx
 * needs (column-major), and issues a native draw into the current
 * SubScene's shared color+depth target. Mirrors {@code ES2MeshView}.
 * See docs/3D.md (Door 1).</p>
 */
final class SkiaMeshView extends BaseMeshView {

    private static final AtomicInteger count = new AtomicInteger();

    private final long nativeHandle;
    private final SkiaMesh mesh;
    private SkiaPhongMaterial material;
    private boolean countReleased;

    // Render-thread scratch, reused every pulse. The render thread is single-
    // threaded and each mesh view renders at most once per pulse, so per-instance
    // scratch is safe; NativeBridge3D.draw3D copies these into a confined arena
    // synchronously before render() returns. CLAUDE.md forbids per-frame `new` on
    // the render thread — uncapped fps × N shapes makes the old per-draw float[16]
    // ×2 + GeneralTransform3D + Vec3d a real GC-churn source.
    private final float[] projViewScratch = new float[16];
    private final float[] modelScratch = new float[16];
    private final GeneralTransform3D pvScratch = new GeneralTransform3D();
    private final Vec3d camPosScratch = new Vec3d();

    private SkiaMeshView(long nativeHandle, SkiaMesh mesh, Disposer.Record disposerRecord) {
        super(disposerRecord);
        this.nativeHandle = nativeHandle;
        this.mesh = mesh;
        count.incrementAndGet();
    }

    static SkiaMeshView create(SkiaMesh mesh) {
        long h = NativeBridge3D.meshViewCreate(mesh.getNativeHandle());
        return new SkiaMeshView(h, mesh, new SkiaMeshViewDisposerRecord(h));
    }

    @Override
    public void setCullingMode(int mode) {
        NativeBridge3D.meshViewSetCulling(nativeHandle, mode);
    }

    @Override
    public void setMaterial(Material m) {
        // render() and the native draw path already treat a null material as
        // "solid-colour defaults"; mirror that tolerance here so a null or
        // non-Skia material can't NPE / ClassCastException the render thread.
        // (meshViewSetMaterial is a native no-op — the live binding is matHandle
        // passed in render() — but we keep the call for parity / future use.)
        this.material = (m instanceof SkiaPhongMaterial spm) ? spm : null;
        long matHandle = (material != null) ? material.getNativeHandle() : 0L;
        NativeBridge3D.meshViewSetMaterial(nativeHandle, matHandle);
    }

    @Override
    public void setWireframe(boolean wireframe) {
        NativeBridge3D.meshViewSetWireframe(nativeHandle, wireframe);
    }

    @Override
    public void setAmbientLight(float r, float g, float b) {
        NativeBridge3D.meshViewSetAmbient(nativeHandle, r, g, b);
    }

    @Override
    public void setLight(int index, float x, float y, float z,
                         float r, float g, float b, float w,
                         float ca, float la, float qa, float isAttenuated, float maxRange,
                         float dirX, float dirY, float dirZ,
                         float innerAngle, float outerAngle, float falloff) {
        NativeBridge3D.meshViewSetLight(nativeHandle, index, x, y, z, r, g, b, w,
                ca, la, qa, isAttenuated, maxRange, dirX, dirY, dirZ,
                innerAngle, outerAngle, falloff);
    }

    @Override
    public void render(Graphics g) {
        SkiaGraphics sg = (SkiaGraphics) g;
        long target = sg.ensure3DTarget();
        if (target == 0L) {
            return; // not a 3D target (or unavailable) — skip
        }

        NGCamera cam = g.getCameraNoClone();
        GeneralTransform3D pv = cam.getProjViewTx(pvScratch);
        BaseTransform m = g.getTransformNoClone();

        // GeneralTransform3D.get(i) is row-major; bgfx wants column-major → transpose.
        float[] projView = projViewScratch;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                projView[col * 4 + row] = (float) pv.get(row * 4 + col);
            }
        }

        // Node world transform (affine 3x4) → column-major 4x4.
        float[] model = modelScratch;
        model[0]  = (float) m.getMxx(); model[1]  = (float) m.getMyx(); model[2]  = (float) m.getMzx(); model[3]  = 0f;
        model[4]  = (float) m.getMxy(); model[5]  = (float) m.getMyy(); model[6]  = (float) m.getMzy(); model[7]  = 0f;
        model[8]  = (float) m.getMxz(); model[9]  = (float) m.getMyz(); model[10] = (float) m.getMzz(); model[11] = 0f;
        model[12] = (float) m.getMxt(); model[13] = (float) m.getMyt(); model[14] = (float) m.getMzt(); model[15] = 1f;

        Vec3d camPos = cam.getPositionInWorld(camPosScratch);

        if (material != null) {
            material.lockTextureMaps();
        }
        long matHandle = (material != null) ? material.getNativeHandle() : 0L;
        NativeBridge3D.draw3D(target, nativeHandle, matHandle, projView, model,
                (float) camPos.x, (float) camPos.y, (float) camPos.z);
        if (material != null) {
            material.unlockTextureMaps();
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void dispose() {
        // Idempotent (see SkiaMesh.dispose): decrement the live-view counter once.
        material = null;
        disposerRecord.dispose();
        if (!countReleased) {
            countReleased = true;
            count.decrementAndGet();
        }
    }

    static final class SkiaMeshViewDisposerRecord implements Disposer.Record {
        private long handle;
        SkiaMeshViewDisposerRecord(long handle) { this.handle = handle; }
        @Override public void dispose() {
            if (handle != 0L) {
                NativeBridge3D.meshViewDestroy(handle);
                handle = 0L;
            }
        }
    }
}
