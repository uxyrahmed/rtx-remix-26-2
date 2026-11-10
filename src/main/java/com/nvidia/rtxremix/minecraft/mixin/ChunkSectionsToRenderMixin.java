/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.VanillaTerrainDraws;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla's terrain draws while Remix renders the world: Remix owns that geometry, and
 * a captured section has no layers for vanilla to draw anyway. Every terrain layer group, the
 * opaque one and the translucent one, is drawn through here.
 *
 * <p>The draw opens its own render pass, binds its pipeline and textures to it and closes it
 * before returning, so skipping it leaves no state behind that later passes would read; there
 * is nothing to replay.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainDraw(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        boolean skip = RtxRemixMod.isRemixWorldRendering();
        VanillaTerrainDraws.recordCall(skip);
        if (skip) {
            ci.cancel();
        }
    }
}
