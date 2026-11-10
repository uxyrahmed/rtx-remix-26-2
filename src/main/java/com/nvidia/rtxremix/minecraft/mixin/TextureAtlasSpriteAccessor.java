/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {
    /**
     * Texels of edge padding around the sprite in its atlas slot: the slot starts
     * at the sprite's {@code getX()}/{@code getY()} and its contents, which the UVs
     * address, start {@code padding} texels further in on both axes.
     */
    @Accessor("padding")
    int getPadding();
}
