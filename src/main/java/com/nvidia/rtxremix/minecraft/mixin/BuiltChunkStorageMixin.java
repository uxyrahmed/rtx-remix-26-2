/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BuiltChunkNeighbourhoodAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Consumer;

/**
 * Section-addressed access to the view area's slots for the terrain pipeline; see
 * {@link BuiltChunkStorageSectionAccess}.
 *
 * <p>Vanilla repositions the slot window itself: it returns at once when the camera section
 * has not changed and re-nodes only the slots whose section did, which is what the mod's own
 * repositioning used to do for the per-frame full scan vanilla made before.
 */
@Mixin(ViewArea.class)
public abstract class BuiltChunkStorageMixin implements BuiltChunkStorageSectionAccess {

    @Shadow
    @Final
    private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

    @Shadow
    public abstract int minSectionY();

    @Shadow
    public abstract int maxSectionY();

    @Override
    public SectionRenderDispatcher.@Nullable RenderSection rtxremix$residentSection(int cx, int sy, int cz) {
        return sections.getValue(cx, sy, cz);
    }

    @Override
    public void rtxremix$forEachSection(Consumer<SectionRenderDispatcher.RenderSection> action) {
        sections.forEach(action);
    }

    @Override
    public void rtxremix$clearNeighbourSnapshotBits(int cx, int cz) {
        int bottomSection = minSectionY();
        int topSection = maxSectionY();
        for (int k = 0; k < NeighbourReflagFilter.NEIGHBOUR_COUNT; k++) {
            int bit = NeighbourReflagFilter.bit(NeighbourReflagFilter.mirroredIndex(k));
            int neighbourX = cx + NeighbourReflagFilter.DX[k];
            int neighbourZ = cz + NeighbourReflagFilter.DZ[k];
            for (int sy = bottomSection; sy <= topSection; sy++) {
                SectionRenderDispatcher.RenderSection slot = sections.getValue(neighbourX, sy, neighbourZ);
                if (slot != null) {
                    ((BuiltChunkNeighbourhoodAccess) slot).rtxremix$clearSnapshotBit(bit);
                }
            }
        }
    }
}
