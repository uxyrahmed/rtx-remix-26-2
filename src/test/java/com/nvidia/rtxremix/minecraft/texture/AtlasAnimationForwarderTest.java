/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasAnimationForwarderTest {

    /**
     * The blend the GPU's interpolation now draws, rebuilt on the CPU as vanilla once
     * computed it: colour weighted toward the current frame by the share of its time
     * still to show, alpha the current frame's.
     */
    @Test
    void interpolationBlendsColourByTheCurrentFrameWeightAndKeepsItsAlpha() {
        try (NativeImage sheet = new NativeImage(2, 1, true);
             NativeImage blended = new NativeImage(1, 1, true)) {
            sheet.setPixelABGR(0, 0, 0xFF0A1464);
            sheet.setPixelABGR(1, 0, 0x201E28C8);

            AtlasAnimationForwarder.blendFrames(sheet, blended, 1, 1, 0, 0, 1, 0, 0.25);

            assertEquals(0xFF1923AF, MemoryUtil.memGetInt(blended.getPointer()));
        }
    }

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void addPatchIgnoresSpritesOutsideTheBlockAtlasAllowList() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier guiSprite = Identifier.fromNamespaceAndPath("minecraft", "gui/sprites/icon");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);

        try {
            AtlasAnimationForwarder.addPatch(guiSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());

            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(1, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void addPatchIgnoresAllowedSpriteIdsFromOtherAtlases() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier particleAtlas = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(particleAtlas);

        try {
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    private static int pendingPatchCount() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return ((List<?>) pendingPatches.get(null)).size();
    }
}
