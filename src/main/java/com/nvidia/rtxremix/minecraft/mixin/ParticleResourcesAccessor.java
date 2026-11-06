/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ParticleResources.class)
public interface ParticleResourcesAccessor {
    /**
     * Sprite sets keyed by particle type id. The value class is private in
     * vanilla, so callers read it through the public {@link SpriteSet} it
     * implements.
     */
    @Accessor("spriteSets")
    Map<Identifier, ? extends SpriteSet> getSpriteSets();
}
