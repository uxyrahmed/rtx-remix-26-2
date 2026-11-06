/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Frame sequence of an animated sprite. The class and its frame record are
 * private in vanilla and widened by the class tweaker so they can be named.
 */
@Mixin(SpriteContents.AnimatedTexture.class)
public interface SpriteAnimatedTextureAccessor {
    /** Displayed frames in playback order, each a sheet index and a duration in ticks. */
    @Accessor("frames")
    List<SpriteContents.FrameInfo> getFrames();

    /** Frames per row of the sprite sheet. */
    @Accessor("frameRowSize")
    int getFrameRowSize();

    /** Whether the frames blend into the next one over their duration. */
    @Accessor("interpolateFrames")
    boolean isInterpolateFrames();
}
