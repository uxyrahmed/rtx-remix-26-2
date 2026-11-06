/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Sprite table and size of a stitched atlas. The size accessors are not named
 * {@code getWidth}/{@code getHeight}: the atlas declares package-private methods
 * with exactly those signatures.
 */
@Mixin(TextureAtlas.class)
public interface SpriteAtlasAccessor {
    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> getSprites();

    @Accessor("width")
    int getAtlasWidth();

    @Accessor("height")
    int getAtlasHeight();
}
