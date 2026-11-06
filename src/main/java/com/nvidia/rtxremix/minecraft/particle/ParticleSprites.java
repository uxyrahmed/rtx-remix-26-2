/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import com.nvidia.rtxremix.minecraft.mixin.ParticleResourcesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The sprite sets vanilla binds to particle types, for drawing a particle's
 * sprites outside the particle engine.
 *
 * <p>The sets belong to the engine's {@code ParticleResources} and are rebound,
 * not replaced, on every resource reload, so a set returned here keeps
 * following resource packs.
 */
public final class ParticleSprites {

    private ParticleSprites() {}

    /**
     * Returns the sprite set vanilla registered for {@code type}, or null when
     * the type has no sprite-driven provider or the engine does not exist yet.
     */
    public static @Nullable SpriteSet forType(ParticleType<?> type) {
        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        if (engine == null) {
            return null;
        }
        ParticleResourcesAccessor resources =
                (ParticleResourcesAccessor) ((ParticleManagerAccessor) engine).getResources();
        return resources.getSpriteSets().get(BuiltInRegistries.PARTICLE_TYPE.getKey(type));
    }
}
