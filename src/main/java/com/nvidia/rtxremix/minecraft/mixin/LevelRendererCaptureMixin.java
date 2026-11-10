/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.CaptureHookStats;
import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures entities and block-breaking cracks where {@code LevelRenderer} submits them,
 * instead of letting them reach vanilla's feature rendering and the OpenGL frame that
 * Remix's image replaces.
 *
 * <p>{@code submitFeatures} submits the render states the frame's extraction produced:
 * every visible entity through {@code EntityRenderDispatcher.submit}, then block entities
 * (captured by {@code BlockEntityRenderDispatcherMixin}), then the cracks of blocks being
 * broken. Each entity is offered to {@code RemixRenderer.renderEntityInPlace}, which has
 * its renderer submit into the Remix capture; vanilla keeps ownership of animation,
 * render layers and modded entity support, but the model is evaluated once, for Remix.
 * This traversal covers only what vanilla extracted, which is frustum culled, so the
 * entities the camera cannot see are picked up afterwards by the Remix pass, which needs
 * them for shadows and reflections. When Remix declines, vanilla submits as usual.
 *
 * <p>A crack is handed to {@code RemixRenderer.renderBlockDamageInPlace} with its broken
 * block. Vanilla's matrix stack and collector are deliberately not forwarded: the stack is
 * camera-relative and the collector draws into OpenGL, while the capture supplies an
 * identity stack and its own crack projection so the geometry stays block-local.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCaptureMixin {

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void rtxremix$beginLevelSubmit(LevelRenderState levelRenderState,
                                           SubmitNodeCollector submitNodeCollector,
                                           boolean renderOutline, CallbackInfo ci) {
        CaptureHookStats.beginLevelSubmit();
    }

    @WrapOperation(
            method = "submitEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            EntityRenderDispatcher dispatcher,
            EntityRenderState state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector output,
            Operation<Void> original) {
        CaptureHookStats.noteEntitySubmitted();
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && renderer.renderEntityInPlace(state, camera, x, y, z, output);
        if (!captured) {
            // Remix is not drawing this entity, so vanilla's frame is what the user
            // sees and it keeps its blob shadow.
            original.call(dispatcher, state, camera, x, y, z, poseStack, output);
        }
    }

    /**
     * Fabric's renderer API redirects this same call to submit the crack as a mesh, after
     * cancelling vanilla's part collection; this wrap runs around that redirect, so a
     * declined crack still reaches Fabric's path, and a captured one collects its block's
     * model itself rather than read the list Fabric empties.
     */
    @WrapOperation(
            method = "submitBlockDestroyAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;I)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            List<BlockStateModelPart> parts,
            int progress,
            Operation<Void> original,
            @Local BlockBreakingRenderState state) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(state.blockPos(), progress,
                        (captureMatrices, captureConsumer) -> RemixBlockDamagePass.putModel(
                                state.blockState(), state.blockPos(), captureMatrices, captureConsumer));
        if (!captured) {
            original.call(collector, poseStack, parts, progress);
        }
    }
}
