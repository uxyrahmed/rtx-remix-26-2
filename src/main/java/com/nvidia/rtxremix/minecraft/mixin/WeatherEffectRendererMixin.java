/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the frame's rain and snow to {@link RemixWeatherRenderer} instead of drawing
 * them while Remix draws the world; the weather is drawn over the Remix image once
 * it is composited.
 *
 * <p>The cancelled draw is self-contained: it builds and uploads its own vertex
 * buffer and records its own render pass, so skipping it leaves no state behind. The
 * world border, drawn after it in the same pass, is untouched.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureWeather(Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return;
        }
        RemixWeatherRenderer weather = RtxRemixMod.getInstance().getRenderer().getWeatherRenderer();
        if (weather == null) {
            return;
        }
        weather.capture((WeatherEffectRenderer) (Object) this, cameraPos, renderState);
        ci.cancel();
    }
}
