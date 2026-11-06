/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Quad state of a camera-facing particle: size, tint, roll, sprite and the
 * layer that names its atlas.
 */
@Mixin(SingleQuadParticle.class)
public interface BillboardParticleAccessor {
    @Accessor("quadSize")
    float getScale();

    @Accessor("rCol")
    float getRed();

    @Accessor("gCol")
    float getGreen();

    @Accessor("bCol")
    float getBlue();

    @Accessor("alpha")
    float getAlpha();

    @Accessor("roll")
    float getAngle();

    @Accessor("oRoll")
    float getPrevAngle();

    @Accessor("sprite")
    TextureAtlasSprite getSprite();

    @Invoker("getU0")
    float invokeGetMinU();

    @Invoker("getU1")
    float invokeGetMaxU();

    @Invoker("getV0")
    float invokeGetMinV();

    @Invoker("getV1")
    float invokeGetMaxV();

    @Invoker("getLayer")
    SingleQuadParticle.Layer invokeGetLayer();
}
