/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies {@link UnfocusedFramerateLimit} while the window is unfocused, so a
 * game left behind other windows does not hold the GPU at path-tracing load.
 * Without an initialized Remix renderer the game draws through vanilla, so
 * the limit is left to vanilla too.
 *
 * <p>Since 1.21.2 the frame cap comes from {@link FramerateLimitTracker}, which
 * already lowers it for an iconified or idle window but not for one that is
 * merely behind other windows. {@code Minecraft.renderFrame} reads it once per
 * frame and {@code FramerateLimiter.limitDisplayFPS} sleeps the render thread to
 * it. At Unlimited, or at any cap the frame rate does not reach, the loop never
 * sleeps, and with the Remix present path Windows then marks an unfocused
 * window as not responding. {@code rtx.conf} enables
 * {@code rtx.enableInputQueuePing}, which keeps the window responsive at every
 * setting.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class UnfocusedFramerateMixin {

    @ModifyReturnValue(method = "getFramerateLimit()I", at = @At("RETURN"))
    private int rtxremix$capWhileUnfocused(int limit) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.isWindowActive()) {
            return limit;
        }
        // A benchmark measures renderer throughput and must not be throttled
        // because the user tabbed away while it ran.
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.isInitialized()
                || renderer.getBenchmarkController().isRunning()) {
            return limit;
        }
        return UnfocusedFramerateLimit.apply(limit);
    }
}
