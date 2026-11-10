/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceScanPolicy;
import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceAccess;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seeds the sky-light surface scan of a chunk the client receives from the packet's
 * {@code WORLD_SURFACE} heightmap. {@code replaceWithPacketData} is the client chunk apply;
 * it installs the packet's heightmaps just before it initializes the light sources, so the
 * seed is the received height. The server's refresh during chunk generation is untouched.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkMixin {

    @WrapOperation(
            method = "replaceWithPacketData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;initializeLightSources()V"))
    private void rtxremix$seedSurfaceScan(
            LevelChunk self,
            Operation<Void> original,
            FriendlyByteBuf buf,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer) {
        // A chunk reloaded in place keeps its previous heightmaps, so only a heightmap the
        // packet itself carried is a valid seed.
        boolean worldSurfaceInPacket = heightmaps.containsKey(Heightmap.Types.WORLD_SURFACE);
        if (!SkyLightSurfaceScanPolicy.seedFromHeightmap(SkyLightSurfaceScanPolicy.SEED_ALLOWED, worldSurfaceInPacket)) {
            if (SkyLightSurfaceScanPolicy.SEED_ALLOWED) {
                SkyLightSurfaceScanPolicy.noteMissingHeightmap();
            }
            original.call(self);
            return;
        }
        ((SkyLightSurfaceAccess) self.getSkyLightSources())
                .rtxremix$refreshSurfaceY(self, self.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE));
        // Counted only while capture is on, like the paced-chunk counters: the terrain
        // counters that drain it run only while capture and world rendering are on, and
        // seeding itself depends on neither toggle.
        if (RtxRemixMod.isRtxEnabled()) {
            SkyLightSurfaceScanPolicy.recordSeeded();
        }
    }
}
