/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A block keeps its tint: staging multiplies the block's tint into a captured quad's vertex
 * color as Indigo does. That a captured quad carries its tint index, for vanilla and modded
 * models alike, is covered under the Fabric renderer by {@link RemixRenderContextTest}.
 */
class FrapiBlockTintTest {

    @Test
    void theTintMultipliesEachChannelAndKeepsTheVertexAlpha() {
        assertEquals(0xFF7FB238, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0x7FB238));
        // 0x80 * 0x80 / 0xFF rounds down to 0x40, as Indigo's color multiply does.
        assertEquals(0x40404040, BlockQuadEmitter.tintFrapiColor(0x40808080, 0x808080));
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, 0xFFFFFF));
    }

    @Test
    void aBlockWithoutATintSourceKeepsTheVertexColor() {
        // A tint index past the block's tint sources resolves to -1.
        assertEquals(0x80336699, BlockQuadEmitter.tintFrapiColor(0x80336699, -1));
    }

    @Test
    void aVanillaQuadTakesTheOpaqueTint() {
        // A vanilla quad's vertex color is white, so its staged color is the tint made opaque,
        // whatever alpha the tint source reports.
        assertEquals(0xFF91BD59, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0x0091BD59));
        assertEquals(0xFF91BD59, BlockQuadEmitter.tintFrapiColor(0xFFFFFFFF, 0xFF91BD59));
    }
}
