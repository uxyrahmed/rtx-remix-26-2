/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Playback position of one animated sprite, which vanilla advances once per atlas tick. */
@Mixin(SpriteContents.AnimationState.class)
public interface SpriteAnimationStateAccessor {
    /** Index into the frame sequence of the frame on show. */
    @Accessor("frame")
    int getFrame();

    /** Ticks the frame on show has been shown, restarting at 0 on each advance. */
    @Accessor("subFrame")
    int getSubFrame();

    /** Whether the last tick moved to a frame with a different sheet index. */
    @Accessor("isDirty")
    boolean isDirty();

    @Accessor("animationInfo")
    SpriteContents.AnimatedTexture getAnimationInfo();
}
