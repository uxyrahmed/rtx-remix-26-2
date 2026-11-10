/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixRenderStateOwner;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tags each extracted entity render state with its entity, and brackets each entity
 * submit with a capture boundary on the thread-local {@link EntityRenderContext}.
 *
 * <p>{@code extractEntity} is the one place vanilla turns an entity into a render state,
 * for the level, for a spawner's display entity and for an item pickup alike, so the
 * owner recorded at its return is what lets the capture key the state's parts by the
 * entity itself; see {@link RemixRenderStateOwner}.
 *
 * <p>{@code submit()} opens the boundary at HEAD with the remix-pass flag, set when the
 * collector is the Remix capture's, and closes it at RETURN. Nesting is the context's
 * concern: an entity submitted from inside another submit, such as a spawner's display
 * entity inside its block entity's capture, appends to the enclosing capture instead of
 * starting its own. The capture evaluates each node as it is submitted, so the nested
 * entity's bones land inside the boundary.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    private static final String rtxremix$SUBMIT =
            "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDD"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V";

    @Inject(
        method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
        at = @At("RETURN")
    )
    private <E extends Entity> void rtxremix$recordOwner(E entity, float partialTicks,
                                                         CallbackInfoReturnable<EntityRenderState> cir) {
        ((RemixRenderStateOwner) cir.getReturnValue()).rtxremix$setOwner(entity);
    }

    @Inject(method = rtxremix$SUBMIT, at = @At("HEAD"))
    private <S extends EntityRenderState> void rtxremix$onSubmitStart(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().begin(submitNodeCollector instanceof RemixVertexConsumerProvider);
    }

    @Inject(method = rtxremix$SUBMIT, at = @At("RETURN"))
    private <S extends EntityRenderState> void rtxremix$onSubmitEnd(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().end();
    }
}
