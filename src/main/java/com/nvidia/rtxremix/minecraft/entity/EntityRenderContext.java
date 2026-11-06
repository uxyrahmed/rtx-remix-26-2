/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.InteractionHand;

/**
 * Thread-local context tracking whether a Remix capture is open and which rigid
 * pivot (ModelPart/Cuboid) is active.
 *
 * <p>Capture boundaries are opened by a Remix capture pass around the vanilla
 * submit it captures, and by {@code EntityRenderDispatcherMixin} around every entity
 * submit. Boundaries nest: only the outermost {@link #begin} starts a fresh capture,
 * and an inner one (an entity submitted from inside another submit, such as a
 * spawner's display entity) appends its bones to the enclosing capture, exactly as
 * render-layer overlays already do. Since 26.x a capture evaluates every submit the
 * moment it is made (see {@link RemixVertexConsumerProvider}), so the bones of a
 * submit land inside the boundary that was open when it was submitted. Bone
 * boundaries are detected by {@link RemixCapturingConsumer} when the
 * {@code PoseStack.Pose} changes between vertices.
 *
 * <h3>Rigid pivot tracking</h3>
 * <p>Each time the pose matrix changes during vertex capture, a transform
 * index is assigned via {@link #pushBone(Matrix4f)}. The submitter pairs the
 * local cuboid/item mesh with that ordinary instance transform; no vertex
 * deformation or Remix skinning is involved.
 */
public final class EntityRenderContext {

    private static final ThreadLocal<EntityRenderContext> CURRENT = ThreadLocal.withInitial(EntityRenderContext::new);

    /** {@link #getBlockItemLayer()} outside the render of a block item. */
    public static final int NO_BLOCK_ITEM = -1;

    /** Open capture boundaries; the capture is active while this is positive. */
    private int depth;

    /** Whether we are in a Remix capture pass (vs vanilla raster). */
    private boolean remixPass;

    /** First-person hand currently being rendered, or null outside that path. */
    private InteractionHand activeHand;
    /**
     * The flame particle of the burning lit element of the item being submitted, or
     * null; see {@link #setPendingHeldFlame}.
     */
    private SimpleParticleType pendingHeldFlame;
    private int blockItemLayer = NO_BLOCK_ITEM;

    /** The viewer's eye in the capture's space, valid while {@link #eyeKnown}. */
    private final Vector3f eye = new Vector3f();
    private boolean eyeKnown;

    /** Current bone index counter (incremented per ModelPart.render). */
    private int boneCounter;

    /** Rigid pivot transforms collected during this capture.
     *  Pre-allocated Matrix4f objects are reused across captures to avoid GC pressure. */
    private final List<Matrix4f> boneTransforms = new ArrayList<>();
    /** Hand ownership parallel to {@link #boneTransforms}; enum values are allocation-free. */
    private final List<InteractionHand> transformHands = new ArrayList<>();
    private int bonePoolHighWater = 0;

    // ---- Static access ----

    public static EntityRenderContext get() {
        return CURRENT.get();
    }

    // ---- Capture lifecycle ----

    /**
     * Opens a capture boundary. The outermost call starts a fresh capture: it
     * records {@code remixPass}, clears the per-item state and restarts bone
     * numbering. A nested call only deepens the boundary, so the inner render's
     * bones append to the enclosing capture.
     */
    public void begin(boolean remixPass) {
        if (depth++ > 0) {
            return;
        }
        this.remixPass = remixPass;
        this.activeHand = null;
        this.pendingHeldFlame = null;
        this.blockItemLayer = NO_BLOCK_ITEM;
        this.eyeKnown = false;
        this.boneCounter = 0;
        // Bone transforms are not cleared: the pre-allocated Matrix4f objects are
        // reused and boneCounter bounds the valid range [0, boneCounter).
    }

    /**
     * Closes the innermost open boundary. Closing the outermost one ends the
     * capture but leaves the bone count readable, because the capture owner
     * snapshots the bones after vanilla's dispatcher has returned.
     */
    public void end() {
        if (depth == 0 || --depth > 0) {
            return;
        }
        closeCapture();
    }

    /**
     * Closes the capture whatever its depth. Every capture owner calls this in a
     * {@code finally}: a renderer that throws skips the dispatcher's RETURN hook,
     * and a boundary left open would make every later {@link #begin} look nested
     * and never restart bone numbering.
     */
    public void reset() {
        depth = 0;
        closeCapture();
    }

    private void closeCapture() {
        this.remixPass = false;
        this.activeHand = null;
        this.pendingHeldFlame = null;
        this.blockItemLayer = NO_BLOCK_ITEM;
        this.eyeKnown = false;
    }

    /** Whether a capture boundary is open at any depth. */
    public boolean isActive() {
        return depth > 0;
    }

    /**
     * The terrain layer of the block item currently being rendered, as a
     * {@code BlockTextureManager} layer type, or {@link #NO_BLOCK_ITEM}. Ice and
     * stained glass are {@code L_TRANS}, clear glass {@code L_GLASS}.
     *
     * <p>Set by {@code ItemStackRenderStateMixin} for the duration of one
     * {@code ItemStackRenderState.submit} call inside a capture, which evaluates the
     * item's quads before it returns, and sampled by {@link RemixCapturingConsumer} as
     * each part opens, so the answer travels with the geometry that item produced.
     */
    public int getBlockItemLayer() {
        return blockItemLayer;
    }

    public void setBlockItemLayer(int blockItemLayer) {
        this.blockItemLayer = blockItemLayer;
    }

    /**
     * The viewer's eye in the capture's space, or null when the capture owner
     * has not supplied it. The first-person pass knows it, because its capture
     * is view-relative; an entity capture does not.
     */
    public Vector3fc getEye() {
        return eyeKnown ? eye : null;
    }

    public void setEye(float x, float y, float z) {
        eye.set(x, y, z);
        eyeKnown = true;
    }

    // ---- Bone tracking ----

    /**
     * Called at each {@code ModelPart.Cube.compile()} to register a rigid pivot.
     *
     * <p>Bones are appended in traversal order. Several submits can be made for
     * one entity (base model plus render-layer overlays) and each appends
     * its own bones to the same array, so an index is absolute across the whole
     * capture rather than relative to a layer.
     *
     * @param pose the 4x4 model-to-world transform at this cuboid
     * @return the bone index assigned to this cuboid
     */
    public int pushBone(Matrix4fc pose) {
        int index = boneCounter++;
        if (index < boneTransforms.size()) {
            boneTransforms.get(index).set(pose);
            transformHands.set(index, activeHand);
        } else {
            // Grows only until the high-water mark is reached.
            boneTransforms.add(new Matrix4f(pose));
            transformHands.add(activeHand);
        }
        if (boneCounter > bonePoolHighWater) {
            bonePoolHighWater = boneCounter;
        }
        return index;
    }

    /**
     * Returns the most recently pushed bone index, which
     * {@code RemixCapturingConsumer.detectBoneChange} reads to tag vertices.
     */
    public int currentBoneIndex() {
        return boneCounter > 0 ? boneCounter - 1 : -1;
    }

    /** Marks subsequent first-person transforms as belonging to {@code hand}. */
    public void setActiveHand(InteractionHand hand) {
        activeHand = hand;
    }

    public void clearActiveHand() {
        activeHand = null;
    }

    /**
     * Records that the item about to be submitted carries a lit element that is
     * burning, as its flame particle. Set by {@code ItemStackRenderStateMixin} when an
     * item's submit begins inside a capture; the holder's {@code HeldLightState}
     * decision was taken when the item's render state was extracted, the last point at
     * which the holder is known. The first model layer of the item takes it with
     * {@link #takePendingHeldFlame} once its quads are emitted, while the item's pose
     * is still on the stack, so the flame is emitted once per item.
     */
    public void setPendingHeldFlame(SimpleParticleType flameParticle) {
        pendingHeldFlame = flameParticle;
    }

    /** Returns the pending held flame, see {@link #setPendingHeldFlame}, and clears it. */
    public SimpleParticleType takePendingHeldFlame() {
        SimpleParticleType flame = pendingHeldFlame;
        pendingHeldFlame = null;
        return flame;
    }

    /** Returns first-person hand ownership for a captured transform, if any. */
    public InteractionHand handForTransform(int transformIndex) {
        return transformIndex >= 0 && transformIndex < boneCounter
                ? transformHands.get(transformIndex)
                : null;
    }

    // ---- Accessors ----

    public boolean isRemixPass() {
        return remixPass;
    }

    public int getBoneCount() {
        return boneCounter;
    }

    /**
     * Returns the collected rigid pivot transforms. The list is valid until the
     * next outermost {@link #begin} restarts bone numbering.
     * Returns a subList view, no allocation.
     */
    public List<Matrix4f> getBoneTransforms() {
        return boneTransforms.subList(0, boneCounter);
    }

    /**
     * Snapshots the current bone transforms into {@code reuse} when it is
     * large enough, returning the array that contains the packed data.
     * Layout: 12 floats per bone (3 rows x 4 cols of the transform matrix).
     *
     * <p>The returned array is only stable until the caller reuses it. This is
     * intended for immediate submit paths that consume the data before the next
     * entity render.
     */
    public float[] snapshotBoneTransforms(float[] reuse) {
        if (boneCounter == 0) return EMPTY_BONES;
        int length = boneCounter * 12;
        float[] data = reuse.length >= length ? reuse : new float[length];
        for (int i = 0; i < boneCounter; i++) {
            Matrix4f m = boneTransforms.get(i);
            int off = i * 12;
            data[off]      = m.m00(); data[off + 1]  = m.m10(); data[off + 2]  = m.m20(); data[off + 3]  = m.m30();
            data[off + 4]  = m.m01(); data[off + 5]  = m.m11(); data[off + 6]  = m.m21(); data[off + 7]  = m.m31();
            data[off + 8]  = m.m02(); data[off + 9]  = m.m12(); data[off + 10] = m.m22(); data[off + 11] = m.m32();
        }
        return data;
    }

    private static final float[] EMPTY_BONES = new float[0];
}
