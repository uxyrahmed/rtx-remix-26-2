/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla's cloud draw while Remix draws the world and the mod's own cloud
 * mode is healthy enough to replace it ({@link CloudRenderer#shouldSuppressVanillaClouds}).
 *
 * <p>The draw is the whole of the level render's cloud pass. Skipping it changes no
 * state the caller relies on: vanilla's cloud mesh is rebuilt from its cell and camera
 * keys whenever drawing resumes, and its uniform ring advances at the end of every
 * frame whether or not it was written.
 */
@Mixin(net.minecraft.client.renderer.CloudRenderer.class)
public abstract class VanillaCloudRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaCloudDraw(
            int color,
            CloudStatus cloudStatus,
            float bottomY,
            int range,
            Vec3 cameraPosition,
            long gameTime,
            float partialTicks,
            CallbackInfo ci) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return;
        }
        RemixRenderer renderer = RtxRemixMod.getInstance().getRenderer();
        CloudRenderer clouds = renderer.getCloudRenderer();
        if (clouds != null && clouds.shouldSuppressVanillaClouds()) {
            ci.cancel();
        }
    }
}
