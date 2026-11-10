/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/**
 * Pixel reads in the byte order an RGBA {@link NativeImage} keeps in memory.
 *
 * <p>The texture converters address channels by their position in that order:
 * red in the low byte, then green, blue and alpha. {@link NativeImage#getPixel}
 * returns ARGB instead, so this restores the memory order rather than having
 * every converter swap red and blue.
 */
public final class NativeImagePixels {

    private NativeImagePixels() {}

    /**
     * Returns the pixel at ({@code x}, {@code y}) as {@code 0xAABBGGRR}.
     *
     * @throws IllegalArgumentException if the image is not RGBA or the pixel is outside it
     */
    public static int abgr(NativeImage image, int x, int y) {
        return ARGB.toABGR(image.getPixel(x, y));
    }
}
