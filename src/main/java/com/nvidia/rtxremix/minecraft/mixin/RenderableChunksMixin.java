/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import com.nvidia.rtxremix.minecraft.terrain.visibility.PooledOctree;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityWalkSwitches;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Builds vanilla's visibility state on the buffers {@link VisibilityGraphBuffers} keeps across
 * walks instead of fresh ones sized to the whole view area: the per-slot node map, the map of
 * sections waiting for their chunk, and the visible octree, which becomes a
 * {@link PooledOctree} with vanilla's exact structure. Vanilla's fields and readers are
 * unchanged; they see a node map, an octree and a map, as before.
 *
 * <p>With {@code -Drtxremix.vanillaVisibilityWalk} the octree stays vanilla's own, so the
 * comparison the switch exists for covers the octree as well as the walk.
 */
@Mixin(GraphStorage.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private ChunkInfoPool rtxremix$infoPool;
    private long rtxremix$visibleHash;
    private int rtxremix$visibleCount;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/renderer/SectionOcclusionGraph$SectionToNodeMap;"))
    private SectionToNodeMap rtxremix$reusedNodeMap(int sectionCount) {
        VisibilityGraphBuffers.Buffers buffers = VisibilityGraphBuffers.current();
        rtxremix$infoPool = buffers.infoPool;
        return buffers.infos;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(Lnet/minecraft/core/SectionPos;III)Lnet/minecraft/client/renderer/Octree;"))
    private Octree rtxremix$reusedOctree(SectionPos cameraSection, int renderDistance, int sectionsPerChunk, int minBlockY) {
        if (VisibilityWalkSwitches.VANILLA_WALK) {
            return new Octree(cameraSection, renderDistance, sectionsPerChunk, minBlockY);
        }
        PooledOctree octree = VisibilityGraphBuffers.current().octree;
        octree.reset(cameraSection, renderDistance, sectionsPerChunk, minBlockY);
        return octree;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
    private Long2ObjectOpenHashMap<?> rtxremix$reusedWaitingSections() {
        return VisibilityGraphBuffers.current().waitingForChunkLoads;
    }

    @Override
    public ChunkInfoPool rtxremix$infoPool() {
        return rtxremix$infoPool;
    }

    @Override
    public void rtxremix$recordVisible(SectionRenderDispatcher.RenderSection section) {
        rtxremix$visibleHash += (section.getSectionNode() * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
        rtxremix$visibleCount++;
    }

    @Override
    public long rtxremix$visibleSetHash() {
        return rtxremix$visibleHash;
    }

    @Override
    public int rtxremix$visibleSetSize() {
        return rtxremix$visibleCount;
    }
}
