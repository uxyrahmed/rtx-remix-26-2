/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.CaptureHookStats;
import com.nvidia.rtxremix.minecraft.entity.RemixRenderStateOwner;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures block entities where vanilla submits them, instead of rendering them twice.
 *
 * <p>Vanilla extracts a render state for each visible block entity, then submits the
 * states for drawing; {@code LevelRenderer.submitBlockEntities} is this method's only
 * caller. Each submit is offered to {@code RemixRenderer.renderBlockEntityInPlace}, which
 * has the state's renderer submit into the Remix capture, on the capture's own identity
 * matrix stack, and submits the result; vanilla's storage never sees the state. Vanilla's
 * own matrix stack and collector are deliberately not forwarded: the stack is
 * camera-relative, and the capture supplies an identity one so the geometry stays
 * block-local. When Remix declines, vanilla submits as usual.
 *
 * <p>The block entity is recorded on its render state as vanilla extracts it, because the
 * capture keys retained instances by the block entity's identity and the state carries
 * only its position; see {@link RemixRenderStateOwner}.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(
        method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
        at = @At("RETURN")
    )
    private <E extends BlockEntity> void rtxremix$recordOwner(
            E blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
            boolean isGloballyRendered, CallbackInfoReturnable<BlockEntityRenderState> cir) {
        BlockEntityRenderState state = cir.getReturnValue();
        if (state != null) {
            ((RemixRenderStateOwner) state).rtxremix$setOwner(blockEntity);
        }
    }

    @WrapMethod(
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
    )
    private <S extends BlockEntityRenderState> void rtxremix$captureBlockEntityInPlace(
            S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera, Operation<Void> original) {
        CaptureHookStats.noteBlockEntitySubmitted();
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && ((RemixRenderStateOwner) state).rtxremix$owner() instanceof BlockEntity be
                && be.hasLevel()
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureCollector) ->
                                original.call(state, captureMatrices, captureCollector, camera));
        if (!captured) {
            original.call(state, poseStack, submitNodeCollector, camera);
        }
    }
}
