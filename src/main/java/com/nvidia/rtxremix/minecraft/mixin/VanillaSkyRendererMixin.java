/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.minecraft.sky.VanillaStars;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands vanilla's star mesh to {@link VanillaStars} between building it and
 * uploading it, the only moment its vertices exist on the CPU.
 */
@Mixin(SkyRenderer.class)
public abstract class VanillaSkyRendererMixin {

    @WrapOperation(
            method = "buildStars",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;buildOrThrow()Lcom/mojang/blaze3d/vertex/MeshData;"))
    private MeshData rtxremix$captureStarVertices(BufferBuilder builder, Operation<MeshData> original) {
        MeshData mesh = original.call(builder);
        VanillaStars.capture(mesh);
        return mesh;
    }
}
