/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the shared Remix overlay input policy to Minecraft's mouse callbacks.
 *
 * The gate blocks Minecraft only while the overlay capture session owns input.
 *
 * <p>Since 1.21.9 a button callback carries a {@link MouseButtonInfo} (button and
 * modifiers) and the handler remembers the active one as such; the touchscreen
 * click counter 1.21.1 kept is gone.
 */
@Mixin(MouseHandler.class)
public abstract class MouseMixin implements RemixMouseAccess {
    @Unique
    private static final double rtxremix$HIDDEN_CURSOR_COORDINATE = -Double.MAX_VALUE;

    @Shadow @Final
    private Minecraft minecraft;

    @Shadow
    private MouseButtonInfo activeButton;

    @Shadow
    private void onButton(long handle, MouseButtonInfo rawButtonInfo, int action) { }

    @Unique
    private boolean rtxremix$releasingActiveButton;

    @Override
    public void rtxremix$releaseActiveButton() {
        MouseButtonInfo active = activeButton;
        if (active == null) {
            return;
        }

        rtxremix$releasingActiveButton = true;
        try {
            onButton(minecraft.getWindow().handle(), active, GLFW.GLFW_RELEASE);
        } finally {
            rtxremix$releasingActiveButton = false;
        }
    }

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Shadow
    private boolean ignoreFirstMove;

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockMovementWhileMenuOpen(
            long handle, double cursorX, double cursorY, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            xpos = cursorX;
            ypos = cursorY;
            ignoreFirstMove = false;
            ci.cancel();
        }
    }

    /* Screens derive hover state from these coordinates even without a movement event. */
    @Inject(method = "xpos", at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideXWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "ypos", at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideYWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockLockWhileMenuOpen(CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockClickWhileMenuOpen(
            long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        MouseButtonInfo active = activeButton;
        boolean syntheticRelease = rtxremix$releasingActiveButton
                && action == GLFW.GLFW_RELEASE
                && active != null
                && rawButtonInfo.button() == active.button();
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen() && !syntheticRelease) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockScrollWhileMenuOpen(long handle, double horizontal, double vertical, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }
}
