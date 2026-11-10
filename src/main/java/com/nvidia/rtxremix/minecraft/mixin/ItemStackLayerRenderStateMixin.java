/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds the lit element of a hand-held item right after the item's quads, while the
 * matrix stack still holds the item's block-space pose.
 *
 * <p>A layer's {@code submit} pushes the layer's display transform, submits the quads and
 * pops it again, so the pose is on the stack only around the quad submit; on 1.21.1 the
 * same moment followed {@code ItemRenderer.renderModelLists}. Running here rather than in
 * a hand-specific renderer covers every hand-held render with one hook: the first-person
 * view model, the local player's hidden shadow model, other players and any mob or armor
 * stand. The flame is armed per item by {@code ItemStackRenderStateMixin} and taken by the
 * first layer that submits quads, so an item drawn in several layers still has one flame.
 * Fabric's renderer API redirects the same quad submit to pass a layer's mesh along; this
 * wrap runs around that redirect.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ItemStackLayerRenderStateMixin {

    @Shadow
    private ItemTransform itemTransform;

    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"
            ))
    private void rtxremix$submitHeldFlame(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType,
            Operation<Void> original) {
        original.call(collector, poseStack, displayContext, lightCoords, overlayCoords, outlineColor,
                tintLayers, quads, foilType);
        if (collector instanceof RemixVertexConsumerProvider provider) {
            SimpleParticleType flame = EntityRenderContext.get().takePendingHeldFlame();
            if (flame != null) {
                // A hand draws its item scaled down by its display transform; the flame
                // and smoke are world-sized, so they divide that scale back out.
                HeldItemEffectsRenderer.render(flame, displayContext, poseStack, provider,
                        itemTransform.scale().x());
            }
        }
    }
}
