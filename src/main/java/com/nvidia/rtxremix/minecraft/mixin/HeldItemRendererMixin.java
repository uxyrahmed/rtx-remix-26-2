/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the first-person capture which vanilla hand each arm and item belongs to.
 *
 * <p>{@code submitArmWithItem} submits one hand's arm, map or item. The capture evaluates
 * each node as it is submitted, so every pivot pushed between this method's HEAD and
 * RETURN belongs to that hand, which is how the capture tells the main hand's held
 * beacon from the off hand's.
 *
 * <p>On 1.21.1 this mixin also recorded the holder of every hand-held item for the item
 * renderer's lit-element hook. Since 26.1 the holder is known only while an item's render
 * state is resolved, so {@code ItemModelResolverMixin} takes that decision there.
 */
@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {

    private static final String rtxremix$SUBMIT_ARM_WITH_ITEM =
            "submitArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;"
            + "FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    @Inject(method = rtxremix$SUBMIT_ARM_WITH_ITEM, at = @At("HEAD"))
    private void rtxremix$beginFirstPersonHand(
            AbstractClientPlayer player,
            float frameInterp,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.setActiveHand(hand);
    }

    @Inject(method = rtxremix$SUBMIT_ARM_WITH_ITEM, at = @At("RETURN"))
    private void rtxremix$endFirstPersonHand(
            AbstractClientPlayer player,
            float frameInterp,
            float xRot,
            InteractionHand hand,
            float attack,
            ItemStack itemStack,
            float inverseArmHeight,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int lightCoords,
            CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) context.clearActiveHand();
    }
}
