/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Hooks into vanilla's render section lifecycle.
 *
 * <ul>
 *   <li><b>setSectionNode:</b> Resets per-slot block-entity and snapshot state when
 *       vanilla reuses a section slot for a new section as the camera moves.
 *   <li><b>createCompileTask:</b> Records which neighbour columns the region
 *       snapshot contained, so a later chunk-load re-flag from a column the
 *       capture already read can be dropped.
 *   <li><b>setSectionMesh:</b> Tracks whether the section contains block
 *       entities and flags a mesh without renderable layers as a removal
 *       candidate. The render thread validates the live world section before
 *       removing Remix terrain, because every captured section publishes a mesh
 *       without layers.
 * </ul>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess, BuiltChunkNeighbourhoodAccess {

    @Shadow
    @Final
    public AtomicReference<SectionMesh> sectionMesh;

    @Shadow
    public abstract long getSectionNode();

    private boolean rtxremix$hasBlockEntities;

    /** Bit k set iff neighbour column (DX[k], DZ[k]) was loaded when the last region snapshot was taken. */
    private int rtxremix$snapshotMask;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        return rtxremix$hasBlockEntities;
    }

    @Override
    public int rtxremix$snapshotMask() {
        return rtxremix$snapshotMask;
    }

    @Override
    public void rtxremix$clearSnapshotBit(int bit) {
        rtxremix$snapshotMask &= ~bit;
    }

    @Inject(method = "setSectionNode(J)V", at = @At("HEAD"))
    private void rtxremix$onSetSectionNode(long sectionNode, CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }

    /**
     * The probe sees exactly the columns the snapshot holds: the region cache copies each
     * section through {@code Level.getChunk(x, z)}, which hands back the shared empty chunk
     * precisely when this load-free probe returns null, and the region was created earlier in
     * the same frame on the render thread, with no chunk packet applied in between. Probed
     * when the compile task is created, before a synchronous compile runs it, so a first
     * build still reads an uncompiled mesh here.
     */
    @Inject(method = "createCompileTask", at = @At("RETURN"))
    private void rtxremix$recordSnapshotNeighbourhood(
            RenderSectionRegion region, CallbackInfoReturnable<?> cir) {
        boolean firstBuild = sectionMesh.get() == CompiledSectionMesh.UNCOMPILED;
        long sectionNode = getSectionNode();
        int cx = SectionPos.x(sectionNode);
        int cz = SectionPos.z(sectionNode);
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        int mask = 0;
        if (level != null) {
            for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
                if (level.getChunkSource().getChunk(
                        cx + NeighbourReflagFilter.DX[k], cz + NeighbourReflagFilter.DZ[k], false) != null) {
                    mask |= NeighbourReflagFilter.bit(k);
                }
            }
        }
        rtxremix$snapshotMask = mask;
        if (firstBuild && mask != NeighbourReflagFilter.COMPLETE
                && NeighbourReflagFilter.isIncompleteFirstBuild(true, rtxremix$cameraDistanceSquared(client, sectionNode), mask)) {
            NeighbourReflagFilter.recordIncompleteFirstBuild();
        }
    }

    /** Squared distance from the camera to the section's centre, as vanilla's build priority measured it. */
    private static double rtxremix$cameraDistanceSquared(Minecraft client, long sectionNode) {
        Vec3 camera = client.gameRenderer.mainCamera().position();
        double dx = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)) + 8.0 - camera.x;
        double dy = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)) + 8.0 - camera.y;
        double dz = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)) + 8.0 - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> cir) {
        rtxremix$hasBlockEntities = !mesh.getRenderableBlockEntities().isEmpty();

        if (mesh.hasRenderableLayers()) {
            return;
        }
        long sectionNode = getSectionNode();
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(
                SectionPos.x(sectionNode), SectionPos.y(sectionNode), SectionPos.z(sectionNode));
    }

    @Inject(method = "reset()V", at = @At("HEAD"))
    private void rtxremix$onClear(CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
        rtxremix$snapshotMask = 0;
    }
}
