/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Submits and composites the Remix frame between the first-person hands and the
 * screen overlays, brackets the GUI tail of the render method with a profiling
 * scope, and has input capture follow the Remix menus at the start of each
 * frame.
 *
 * <p>Since 26.1 {@code GameRenderer.renderLevel} draws the level, then submits
 * and renders the first-person hands, then submits and renders the screen
 * effects (fire, pumpkin, water and suffocation overlays) into the same submit
 * storage. The Remix image replaces everything drawn before it, so the frame is
 * composited after the hands and before the screen effects, as on 1.21.1.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"
            ))
    private void rtxremix$submitBeforeScreenOverlays(DeltaTracker deltaTracker, CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * Covers frames where the hand and screen-effect section is not reached the
     * usual way. Submitting is idempotent per frame.
     */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$submitAtRenderWorldTail(DeltaTracker deltaTracker, CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * The GUI tail of GameRenderer.render: HUD, in-game screen or overlay, toasts,
     * drawn by the GUI renderer from the state extracted earlier in the frame.
     * WorldRender.END accounts for the world half of the frame.
     */
    @Unique
    private ProfileScope rtxremix$guiScope;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"
            ))
    private void rtxremix$beginGuiScope(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    /**
     * Before the GUI state is extracted, so the frame's hover state already matches
     * input capture. Since 26.1 screens decide hover while they are extracted, which
     * happens before the render method runs.
     */
    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(DeltaTracker deltaTracker, boolean advanceGameTime,
                                                    CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeGuiScope();
    }

    @Unique
    private void rtxremix$closeGuiScope() {
        ProfileScope scope = rtxremix$guiScope;
        if (scope != null) {
            rtxremix$guiScope = null;
            scope.close();
        }
    }
}
