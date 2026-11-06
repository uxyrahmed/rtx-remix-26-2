/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.LevelExtractorTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Section rebuild scheduling for the terrain pipeline, where 26.2 keeps it: in the level
 * extractor, which owns the per-section dirty flags and turns the dirty visible sections into
 * compiles every frame.
 *
 * <ul>
 *   <li>The water-occlusion margin on block-update rebuilds.</li>
 *   <li>The light-data neighbour re-flag filter; chunk loads reach the same filter from
 *       {@link TerrainChunkLoadReflagMixin}.</li>
 *   <li>The frame's region cache drops its snapshot references once extraction has built
 *       every region it will build.</li>
 * </ul>
 */
@Mixin(LevelExtractor.class)
public abstract class TerrainLevelExtractorMixin implements LevelExtractorTerrainAccess {

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    private @Nullable SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    private boolean shouldInvalidateCompiledGeometry;

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    /** The frame's region cache, a local of {@code extract}; captured at construction, released at return. */
    @Unique
    private RenderRegionCache rtxremix$frameRegionCache;

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild
     * margin. Expand horizontal invalidation so a ceiling or fluid update
     * cannot leave a neighboring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled() || sectionUpdateTracker == null) {
            return;
        }

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }

    /**
     * Light-data re-flags enter here: the client packet listener flags the 3x3x3
     * neighbourhood of each section whose light data arrived. Block updates use the block
     * overloads and stay vanilla. Vanilla's loop is replaced wholesale because the rule must
     * tell the section from its neighbours, which a wrap of the inner per-section call cannot.
     */
    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterNeighbourReflags(int cx, int sy, int cz, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED || !rtxremix$canFilterReflags()) {
            return;
        }
        rtxremix$flagLoadedSection(cx, sy, cz);
        ci.cancel();
    }

    @WrapOperation(
            method = "extract",
            at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureFrameRegionCache(Operation<RenderRegionCache> original) {
        RenderRegionCache cache = original.call();
        rtxremix$frameRegionCache = cache;
        return cache;
    }

    /**
     * Drops the cache's references so the snapshots it cached can be recycled once the
     * compile tasks of this frame's regions have run. See {@link ChunkRendererRegionBuilderMixin}.
     */
    @Inject(method = "extract", at = @At("RETURN"))
    private void rtxremix$releaseFrameRegionCache(
            DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        RenderRegionCache cache = rtxremix$frameRegionCache;
        rtxremix$frameRegionCache = null;
        if (cache != null) {
            ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
        }
    }

    @Override
    public void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz) {
        SectionUpdateTracker tracker = sectionUpdateTracker;
        if (tracker != null) {
            // The tracker answers only for sections inside its window, so this is a no-op for
            // a section that has left it.
            tracker.setDirty(cx, sy, cz, false);
        }
    }

    @Override
    public void rtxremix$flagLoadedSection(int cx, int sy, int cz) {
        SectionUpdateTracker tracker = sectionUpdateTracker;
        ViewArea viewArea = levelRenderer.viewArea();
        ClientLevel world = level;
        if (tracker == null || viewArea == null || world == null) {
            return;
        }
        if (!NeighbourReflagFilter.sectionInRange(sy, world.getMinSectionY(), world.getSectionsCount())) {
            return;
        }
        tracker.setDirty(cx, sy, cz, false);
        BuiltChunkStorageSectionAccess slots = (BuiltChunkStorageSectionAccess) viewArea;
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            SectionUpdateTracker.SectionDirtyState dirty =
                    tracker.getDirtyState(SectionPos.asLong(neighbourX, sy, neighbourZ));
            SectionRenderDispatcher.RenderSection slot = slots.rtxremix$residentSection(neighbourX, sy, neighbourZ);
            boolean resident = dirty != null && slot != null;
            boolean awaitingBuild = resident && dirty.isDirty();
            int snapshotMask = resident ? ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$snapshotMask() : 0;
            int loaderBit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            switch (NeighbourReflagFilter.decideNeighbour(resident, awaitingBuild, snapshotMask, loaderBit)) {
                case FLAG -> {
                    dirty.setDirty(false);
                    NeighbourReflagFilter.recordKept();
                }
                case DROP_IN_SNAPSHOT -> SkippedSectionRebuilds.recordNeighbourInSnapshot();
                case SKIP_NOT_RESIDENT, SKIP_AWAITING_BUILD -> {
                }
            }
        }
    }

    @Override
    public void rtxremix$markCompileScheduled(long sectionNode) {
        SectionUpdateTracker tracker = sectionUpdateTracker;
        if (tracker == null) {
            return;
        }
        SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(sectionNode);
        if (dirty != null) {
            dirty.setNotDirty();
        }
    }

    @Override
    public boolean rtxremix$compiledGeometryInvalidationPending() {
        return shouldInvalidateCompiledGeometry;
    }

    @Override
    public boolean rtxremix$canFilterReflags() {
        return sectionUpdateTracker != null && level != null && levelRenderer.viewArea() != null;
    }
}
