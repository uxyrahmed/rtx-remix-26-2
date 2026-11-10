/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SkyRendererStarsTest {

    @Test
    void starQuadsAreDealtOutToGroupsWithoutLosingAny() {
        int quads = 25;
        float[] positions = new float[quads * 12];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = i;
        }
        float[][] groups = SkyRenderer.splitStarQuads(positions, 4);
        int total = 0;
        for (float[] group : groups) {
            assertEquals(0, group.length % 12, "whole quads only");
            total += group.length;
        }
        assertEquals(positions.length, total);
        assertEquals(7 * 12, groups[0].length, "the remainder goes to the first groups");
        assertEquals(6 * 12, groups[3].length);
        // Quad 5 is the second quad dealt to group 1.
        assertEquals(5 * 12, groups[1][12]);
    }

    @Test
    void aBufferOfPartialQuadsIsRejected() {
        assertThrows(IllegalStateException.class, () -> SkyRenderer.splitStarQuads(new float[13], 4));
        assertThrows(IllegalStateException.class, () -> SkyRenderer.splitStarQuads(new float[0], 4));
    }
}
