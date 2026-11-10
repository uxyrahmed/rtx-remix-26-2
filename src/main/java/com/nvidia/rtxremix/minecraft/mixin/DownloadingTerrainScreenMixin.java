/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.JoinBurstHold;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the terrain-download screen up until Remix's world-join burst has settled.
 *
 * <p>Vanilla closes this screen from its tick as soon as the level is ready, which is while
 * Remix pays its first-sight costs behind the screen. The close is cancelled while
 * {@link JoinBurstHold#keepScreen(long)} says so; vanilla retries every tick, so the screen
 * closes on the first tick after the hold ends.
 *
 * <p>A screen that is still up when the next level starts loading is reused for that level
 * rather than replaced, so the hold is re-armed when the screen is pointed at a new load:
 * each level gets its own hold, as it did when every load opened a new screen.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class DownloadingTerrainScreenMixin {

    private boolean rtxremix$holdBegun;

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void rtxremix$holdUntilSettled(CallbackInfo ci) {
        JoinBurstHold hold = RemixRenderer.activeJoinBurstHold();
        if (hold == null) {
            return;
        }
        long now = System.nanoTime();
        if (!rtxremix$holdBegun) {
            rtxremix$holdBegun = true;
            hold.begin(now);
        }
        if (hold.keepScreen(now)) {
            ci.cancel();
        }
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$rearmForNextLevel(LevelLoadTracker loadTracker, LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        rtxremix$holdBegun = false;
    }
}
