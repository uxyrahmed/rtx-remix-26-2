/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(WeatherEffectRenderer.class)
public interface WeatherEffectRendererAccessor {
    /**
     * Vanilla's column builder: one camera-relative quad per weather column, faded
     * from {@code maxAlpha} toward the weather radius and scaled by the intensity.
     */
    @Invoker("renderInstances")
    void invokeRenderInstances(VertexConsumer builder,
                               List<WeatherEffectRenderer.ColumnInstance> columns,
                               Vec3 cameraPos,
                               float maxAlpha,
                               int radius,
                               float intensity);
}
