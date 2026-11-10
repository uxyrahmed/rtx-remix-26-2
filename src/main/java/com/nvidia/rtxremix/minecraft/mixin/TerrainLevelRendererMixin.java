/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.LevelExtractorTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * The terrain pipeline's side of the level renderer.
 *
 * <ul>
 *   <li>{@link WorldRendererTerrainAccess}: the built-section snapshot and the explicit
 *       recapture queue a terrain recapture uses, and section rebuild requests from the
 *       Remix terrain manager.</li>
 *   <li>The section compile scheduling, the terrain buffer upload, the synchronous near
 *       compiles and the visibility graph update are labelled for Tracy and JFR, under the
 *       names the 1.21.1 phases had.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class TerrainLevelRendererMixin implements WorldRendererTerrainAccess {

    @Unique
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$setupTerrainScope = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$updateChunksScope = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$uploadScope = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$syncRebuildScope = new ThreadLocal<>();

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    /**
     * A recapture requested while the extractor had flagged its compiled geometry for
     * invalidation: the current view area is about to be replaced, and a compile queued on it
     * would be cancelled with it, so the sections are queued on the new one instead.
     */
    @Unique
    private LongOpenHashSet rtxremix$deferredRecapture;

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        if (viewArea != null) {
            ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$clearNeighbourSnapshotBits(cx, cz);
        }
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$forEachSection(section -> {
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        });
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }

        LevelExtractorTerrainAccess extractor = (LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor;
        if (extractor.rtxremix$compiledGeometryInvalidationPending()) {
            // The extractor replaces the view area at its next extraction; the sections are
            // queued on the new one as soon as it exists (rtxremix$queueDeferredRecapture).
            LongOpenHashSet capturable = new LongOpenHashSet();
            for (long sectionNode : sections) {
                if (rtxremix$capturable(level, sectionNode)) {
                    capturable.add(sectionNode);
                }
            }
            if (rtxremix$deferredRecapture == null) {
                rtxremix$deferredRecapture = capturable;
            } else {
                rtxremix$deferredRecapture.addAll(capturable);
            }
            return new LongOpenHashSet(capturable);
        }

        if (viewArea == null || sectionRenderDispatcher == null) {
            return LongSets.emptySet();
        }
        return rtxremix$compileForRemix(level, sections);
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        ((LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor)
                .rtxremix$scheduleRebuildIfResident(cx, sy, cz);
    }

    /**
     * The extractor has just replaced the view area and placed it around the camera: queue a
     * recapture requested while the replacement was pending.
     */
    @Inject(method = "invalidateCompiledGeometry", at = @At("RETURN"))
    private void rtxremix$queueDeferredRecapture(
            ClientLevel level, Options options, Camera camera, BlockColors blockColors, CallbackInfo ci) {
        LongOpenHashSet deferred = rtxremix$deferredRecapture;
        rtxremix$deferredRecapture = null;
        if (deferred != null && !deferred.isEmpty() && viewArea != null && sectionRenderDispatcher != null) {
            rtxremix$compileForRemix(level, deferred);
        }
    }

    /**
     * Same gate as vanilla's compile scheduling: a section built before its column's light is
     * ready would bake bad light, and marking it scheduled below means nothing re-flags it
     * afterwards. An empty section has nothing to capture.
     */
    @Unique
    private static boolean rtxremix$capturable(ClientLevel level, long sectionNode) {
        int cx = SectionPos.x(sectionNode);
        int sy = SectionPos.y(sectionNode);
        int cz = SectionPos.z(sectionNode);
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return false;
        }
        int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
        return sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()
                && !chunk.getSection(sectionIndex).hasOnlyAir()
                && level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(sectionNode));
    }

    /**
     * Queues the capturable sections of {@code sections} that the view area holds for an
     * asynchronous compile, nearest the camera first.
     *
     * @return the sections queued, as packed section positions
     */
    @Unique
    private LongSet rtxremix$compileForRemix(ClientLevel level, LongSet sections) {
        ArrayList<SectionRenderDispatcher.RenderSection> capturable = new ArrayList<>();
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$forEachSection(section -> {
            long sectionNode = section.getSectionNode();
            if (sections.contains(sectionNode) && rtxremix$capturable(level, sectionNode)) {
                capturable.add(section);
            }
        });

        // The compile queue favours sections near the camera, but it only orders what is
        // queued; queueing nearby sections first lets visible holes close promptly while the
        // Remix scene repopulates.
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        capturable.sort(Comparator.comparingDouble(section -> {
            long sectionNode = section.getSectionNode();
            double dx = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)) + 8.0 - camera.x;
            double dy = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)) + 8.0 - camera.y;
            double dz = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)) + 8.0 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }));

        LevelExtractorTerrainAccess extractor = (LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor;
        RenderRegionCache cache = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(capturable.size());
        try {
            for (SectionRenderDispatcher.RenderSection section : capturable) {
                long sectionNode = section.getSectionNode();
                section.compileAsync(cache.createRegion(level, sectionNode));
                // Keep the extractor from scheduling this same section a second time when it
                // next walks the visible sections.
                extractor.rtxremix$markCompileScheduled(sectionNode);
                queued.add(sectionNode);
            }
        } finally {
            ((RegionBuilderRelease) cache).rtxremix$releaseSnapshots();
        }
        return queued;
    }

    // --- Profiling ---

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$updateChunksHead(CameraRenderState camera, CallbackInfo ci) {
        int compileQueue = rtxremix$compileQueueSize();
        if (compileQueue >= 0) {
            ProfileScope.plot("Java.Chunk.toBatchCount", compileQueue);
        }
        // The terrain upload has no per-section count since it moved into one staging upload.
        rtxremix$open(rtxremix$updateChunksScope, "WorldRenderer.updateChunks", -1, compileQueue);
    }

    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$updateChunksReturn(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$updateChunksScope);
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"))
    private void rtxremix$beforeSyncCompile(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$open(rtxremix$syncRebuildScope, "WorldRenderer.updateChunks.build_near_sync", -1, rtxremix$compileQueueSize());
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$afterSyncCompile(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$syncRebuildScope);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void rtxremix$beforeTerrainUpload(CallbackInfo ci) {
        rtxremix$open(rtxremix$uploadScope, "WorldRenderer.updateChunks.upload", -1, -1);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V",
                    shift = At.Shift.AFTER))
    private void rtxremix$afterTerrainUpload(CallbackInfo ci) {
        rtxremix$close(rtxremix$uploadScope);
    }

    /** The visibility graph update, the work 1.21.1's {@code setupTerrain} phase measured. */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void rtxremix$beforeSetupTerrain(CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$push(rtxremix$setupTerrainScope,
                    ProfileScope.beginJfrOnly("WorldRenderer.setupTerrain").plotOnClose("Java.WorldRenderer.setupTerrain.ms"));
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$afterSetupTerrain(CallbackInfo ci) {
        rtxremix$close(rtxremix$setupTerrainScope);
    }

    @Unique
    private int rtxremix$compileQueueSize() {
        return sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getCompileQueueSize();
    }

    @Unique
    private static void rtxremix$open(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            int installCount,
            int dirtyLeaves) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (installCount >= 0) {
            scope.installCount(installCount);
        }
        if (dirtyLeaves >= 0) {
            scope.dirtyLeaves(dirtyLeaves);
        }
        rtxremix$push(holder, scope);
    }

    @Unique
    private static void rtxremix$push(ThreadLocal<ArrayDeque<ProfileScope>> holder, ProfileScope scope) {
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(scope);
    }

    @Unique
    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
