/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.LevelExtractorTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunk-load re-flags under the Remix capture rule ({@link NeighbourReflagFilter}).
 *
 * <p>When a chunk column's light is enabled, vanilla flags the 3x3 columns around it at every
 * section Y for a rebuild, so a section captured while that column was missing is captured
 * again with it present. Each section Y of the loading column goes through the same rule the
 * light-data re-flags go through ({@link TerrainLevelExtractorMixin}): the column's own
 * sections are flagged, and a neighbour section only when its last region snapshot lacked the
 * column. The range covers every section Y of the column, so visiting each Y's horizontal
 * neighbours covers the vertical neighbours vanilla's range also flags.
 */
@Mixin(ClientPacketListener.class)
public abstract class TerrainChunkLoadReflagMixin {

    @WrapOperation(
            method = "enableChunkLight",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionRangeDirty(IIIIII)V"))
    private void rtxremix$filterChunkLoadReflags(
            ClientLevel level, int minSectionX, int minSectionY, int minSectionZ,
            int maxSectionX, int maxSectionY, int maxSectionZ, Operation<Void> original) {
        LevelExtractorTerrainAccess extractor = (LevelExtractorTerrainAccess) Minecraft.getInstance().levelExtractor;
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED
                || !extractor.rtxremix$canFilterReflags()
                || maxSectionX - minSectionX != 2 || maxSectionZ - minSectionZ != 2) {
            original.call(level, minSectionX, minSectionY, minSectionZ, maxSectionX, maxSectionY, maxSectionZ);
            return;
        }
        int chunkX = minSectionX + 1;
        int chunkZ = minSectionZ + 1;
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            extractor.rtxremix$flagLoadedSection(chunkX, sectionY, chunkZ);
        }
    }
}
