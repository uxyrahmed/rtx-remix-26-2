/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves the first-person arm/item draw into Remix while preserving Minecraft's own
 * {@code ItemInHandRenderer} animation and selection logic.
 *
 * <p>{@code GameRenderer.renderItemInHand} submits the hands into its hand-and-screen
 * storage and draws that storage straight after. When the first-person model is to be
 * captured, the submit goes to {@code RemixRenderer.captureFirstPersonModel} instead,
 * which submits the hands into the Remix capture on the same matrix stack, with
 * vanilla's camera-relative root and bob already applied; the storage stays empty, so
 * vanilla's draw that follows draws no hand into the OpenGL frame.
 */
@Mixin(GameRenderer.class)
public abstract class FirstPersonCaptureMixin {

    @WrapOperation(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"
            ))
    private void rtxremix$captureFirstPersonModel(
            ItemInHandRenderer itemInHandRenderer,
            float frameInterp,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            LocalPlayer player,
            int lightCoords,
            Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !RtxRemixMod.shouldCaptureFirstPersonModel()) {
            original.call(itemInHandRenderer, frameInterp, poseStack, submitNodeCollector, player, lightCoords);
            return;
        }
        renderer.captureFirstPersonModel(itemInHandRenderer, frameInterp, poseStack, player, lightCoords);
    }
}
