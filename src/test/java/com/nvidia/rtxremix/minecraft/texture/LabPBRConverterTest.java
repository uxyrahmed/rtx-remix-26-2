/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabPBRConverterTest {

    @Test
    void applyAoToPatchSamplesTheAoTableAtTheUploadSourceOffset() {
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 150, 200, 250);
        putPixel(src, 1, 64, 128, 255, 255);

        byte[] dst = new byte[8];
        byte[] aoTable = bytes(255, 128, 64, 0);

        LabPBRConverter.applyAOToPatch(dst, 8, src, 8, aoTable, 4, 1, 2, 0, 2, 1);

        assertEquals(25, unsigned(dst[0]));
        assertEquals(37, unsigned(dst[1]));
        assertEquals(50, unsigned(dst[2]));
        assertEquals(250, unsigned(dst[3]));
        assertEquals(0, unsigned(dst[4]));
        assertEquals(0, unsigned(dst[5]));
        assertEquals(0, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void buildEmissivePatchSamplesTheAlphaTableAtTheUploadSourceOffset() {
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 100, 100, 255);
        putPixel(src, 1, 200, 50, 20, 255);

        byte[] dst = new byte[8];
        byte[] sAlphaTable = bytes(255, 0, 127, 255);

        boolean anyEmission = LabPBRConverter.buildEmissivePatch(
            dst, 8, src, 8, sAlphaTable, 4, 1, 1, 0, 2, 1, 1.0f);

        assertTrue(anyEmission);
        assertEquals(0, unsigned(dst[0]));
        assertEquals(0, unsigned(dst[1]));
        assertEquals(0, unsigned(dst[2]));
        assertEquals(0, unsigned(dst[3]));
        assertEquals(100, unsigned(dst[4]));
        assertEquals(25, unsigned(dst[5]));
        assertEquals(10, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void buildEmissiveMaskPatchScalesFrameAlbedoByTheMaskAndMarksEmittingTexels() {
        // A 2x1 frame whose mask lights only the right half at 0.5: the left texel must
        // come out fully dark with alpha 0, the right one at half its albedo with alpha 255.
        ByteBuffer src = ByteBuffer.allocate(8);
        putPixel(src, 0, 100, 100, 100, 255);
        putPixel(src, 1, 200, 50, 20, 255);
        byte[] dst = new byte[8];

        LabPBRConverter.buildEmissiveMaskPatch(dst, 8, src, 8, 2, 1, (u, v) -> u > 0.5f ? 0.5f : 0f);

        assertEquals(0, unsigned(dst[0]));
        assertEquals(0, unsigned(dst[3]));
        assertEquals(100, unsigned(dst[4]));
        assertEquals(25, unsigned(dst[5]));
        assertEquals(10, unsigned(dst[6]));
        assertEquals(255, unsigned(dst[7]));
    }

    @Test
    void applyEmissionGainScalesAuthoredEmissionAndLeavesUnauthoredTexelsAlone() {
        // 255 means "no emission" and must survive any gain; everything else scales and
        // caps at 254, the alpha that reads back as factor 1.
        byte[] table = {(byte) 255, (byte) 100, (byte) 200, (byte) 0};
        LabPBRConverter.applyEmissionGain(table, 2.5f);
        assertEquals(255, table[0] & 0xFF, "unauthored stays unauthored");
        assertEquals(250, table[1] & 0xFF, "100 * 2.5");
        assertEquals(254, table[2] & 0xFF, "capped at full emission, not wrapped");
        assertEquals(0, table[3] & 0xFF, "zero stays zero");

        byte[] untouched = {(byte) 255, (byte) 100};
        LabPBRConverter.applyEmissionGain(untouched, 1.0f);
        assertEquals(100, untouched[1] & 0xFF, "gain 1 is a no-op");
    }

    @Test
    void smoothnessBecomesThePerceptualRoughnessThatRemixSquaresItself() {
        // Remix squares its roughness input into GGX alpha, so the complement of LabPBR's
        // perceptual smoothness goes through unsquared, from the atlas blit and the
        // animation table alike.
        try (NativeImage specular = new NativeImage(3, 1, true)) {
            specular.setPixelABGR(0, 0, 0xFF000000);
            specular.setPixelABGR(1, 0, 0xFF000080);
            specular.setPixelABGR(2, 0, 0xFF0000FF);

            byte[] blitted = new byte[3];
            LabPBRConverter.blitRoughness(blitted, 3, specular, 0, 0, 3, 1);
            byte[] table = LabPBRConverter.buildSRoughnessTable(specular);

            for (byte[] roughness : new byte[][] {blitted, table}) {
                assertEquals(255, unsigned(roughness[0]), "smoothness 0 is fully rough");
                assertEquals(127, unsigned(roughness[1]), "smoothness 128");
                assertEquals(0, unsigned(roughness[2]), "smoothness 255 is a mirror");
            }
        }
    }

    @Test
    void normalsKeepTheirAuthoredTiltWhenReadAsRgbTimesTwoMinusOne() {
        // Remix reads a three-channel tangent-space normal as normalize(rgb * 2 - 1). A texel
        // tilted to x = 0.6 must come back with z = 0.8, not steeper, and a flat one with z = 1.
        try (NativeImage normal = new NativeImage(2, 1, true)) {
            normal.setPixelABGR(0, 0, 0xFF0080CC);
            normal.setPixelABGR(1, 0, 0xFF008080);

            byte[] rgba = new byte[8];
            LabPBRConverter.blitNormal(rgba, 2, normal, 0, 0, 2, 1);

            assertEquals(0.6, snorm(rgba[0]), 0.01, "tilted x");
            assertEquals(0.8, snorm(rgba[2]), 0.01, "tilted z");
            assertEquals(1.0, snorm(rgba[6]), 0.01, "flat z");
        }
    }

    @Test
    void heightMapRepeatFitsTheQuadtreeStartCellToTheSmallestSprite() {
        assertEquals(4, LabPBRConverter.heightMapRepeat(2048, 16), "a 16x block atlas starts on 32-texel cells");
        assertEquals(2, LabPBRConverter.heightMapRepeat(1024, 16), "a 1024-texel atlas of 16x sprites");
        assertEquals(1, LabPBRConverter.heightMapRepeat(4096, 64), "64x sprites already fit");
        assertEquals(1, LabPBRConverter.heightMapRepeat(8192, 16), "nothing fits within the size limit");
    }

    @Test
    void repeatTexelsRepeatsEveryTexelInBothDirections() {
        byte[] map = bytes(
                1, 2,
                3, 4);
        assertArrayEquals(bytes(
                1, 1, 2, 2,
                1, 1, 2, 2,
                3, 3, 4, 4,
                3, 3, 4, 4), LabPBRConverter.repeatTexels(map, 2, 2, 2));
        assertSame(map, LabPBRConverter.repeatTexels(map, 2, 2, 1), "a repeat of 1 passes the map through");
    }

    @Test
    void scatteringCoverageIsTheShareOfOpaqueTexelsThatScatter() {
        // A 4x1 region: opaque and scattering, opaque and not, transparent and scattering
        // (not counted at all), opaque and barely scattering.
        byte[] sss = bytes(
                200, 200, 200, 255,
                0, 0, 0, 255,
                200, 200, 200, 255,
                1, 1, 1, 255);
        byte[] albedo = bytes(
                90, 90, 90, 255,
                90, 90, 90, 255,
                90, 90, 90, 0,
                90, 90, 90, 255);

        assertEquals(2f / 3f, LabPBRConverter.scatteringCoverage(sss, albedo, 4, 0, 0, 4, 1), 1e-6f);
        assertEquals(0f, LabPBRConverter.scatteringCoverage(sss, albedo, 4, 2, 0, 1, 1), 0f,
                "a region with no opaque texels scatters nowhere");
    }

    private static void putPixel(ByteBuffer buffer, int pixelIndex, int r, int g, int b, int a) {
        int offset = pixelIndex * 4;
        buffer.put(offset, (byte) r);
        buffer.put(offset + 1, (byte) g);
        buffer.put(offset + 2, (byte) b);
        buffer.put(offset + 3, (byte) a);
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static double snorm(byte value) {
        return unsigned(value) / 255.0 * 2.0 - 1.0;
    }
}
