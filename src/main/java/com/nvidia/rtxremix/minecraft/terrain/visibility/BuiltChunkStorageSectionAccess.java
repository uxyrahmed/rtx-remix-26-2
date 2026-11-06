/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Section-addressed access to the render section slots owned by the view area.
 *
 * <p>The view area keeps one slot per section of the window around the camera and gives a
 * slot the section at its window position whenever the camera moves, so the slot a section
 * coordinate maps to holds that section exactly while the section is inside the window.
 * Render thread only, as the view area itself.
 */
public interface BuiltChunkStorageSectionAccess {

    /** The slot holding section (cx, sy, cz), or null when the section is outside the window. */
    SectionRenderDispatcher.@Nullable RenderSection rtxremix$residentSection(int cx, int sy, int cz);

    /** Visits every slot of the window. */
    void rtxremix$forEachSection(Consumer<SectionRenderDispatcher.RenderSection> action);

    /**
     * Clears the bit for column (cx, cz) in every resident slot of its eight neighbour
     * columns after the column unloaded or before it is reloaded in place, so the next
     * load of that column re-flags them.
     */
    void rtxremix$clearNeighbourSnapshotBits(int cx, int cz);
}
