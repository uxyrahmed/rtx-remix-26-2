/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixItemStackRenderState;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the terrain layer of the block item being submitted, and arms the lit element
 * of a hand-held item, for the duration of one item's submit inside a Remix capture.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer,
 * and clear glass through its cutout one, so the render layer alone cannot tell
 * a pane of stained glass from an apple, or clear glass from a torch. The item can,
 * and since 26.1 it is in scope only while its render state is resolved, so
 * {@code ItemModelResolverMixin} records it on the state ({@link RemixItemStackRenderState})
 * and this mixin hands it to the capture as the state submits its quads. The capture
 * evaluates them before {@code submit} returns, so the answer travels with the geometry
 * this item produced. 1.21.1's {@code ItemRendererMixin} did the same around
 * {@code ItemRenderer.render}, which no longer exists.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements RemixItemStackRenderState {

    private static final String rtxremix$SUBMIT =
            "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V";

    @Unique
    private Block rtxremix$block;
    @Unique
    private SimpleParticleType rtxremix$burningFlame;

    @Override
    public void rtxremix$setCaptureInfo(Block block, SimpleParticleType burningFlame) {
        rtxremix$block = block;
        rtxremix$burningFlame = burningFlame;
    }

    @Override
    public Block rtxremix$block() {
        return rtxremix$block;
    }

    @Override
    public SimpleParticleType rtxremix$burningFlame() {
        return rtxremix$burningFlame;
    }

    @Inject(method = rtxremix$SUBMIT, at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                    int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(rtxremix$blockLayer(rtxremix$block));
            context.setPendingHeldFlame(rtxremix$burningFlame);
        }
    }

    @Inject(method = rtxremix$SUBMIT, at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                  int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
            context.setPendingHeldFlame(null);
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    @Unique
    private static int rtxremix$blockLayer(Block block) {
        return block != null
                ? BlockTextureManager.layerType(BlockQuadEmitter.classifyBlockLayer(block.defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
