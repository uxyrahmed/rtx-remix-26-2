/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Section rebuild scheduling owned by the level extractor, which keeps the per-section dirty
 * flags and turns the dirty visible sections into compiles every frame. Render thread only.
 */
public interface LevelExtractorTerrainAccess {

    /**
     * Flags a section for a vanilla rebuild if it is inside the tracked window. A section
     * outside the window is skipped: the window flags every section that enters it.
     */
    void rtxremix$scheduleRebuildIfResident(int cx, int sy, int cz);

    /**
     * Applies one chunk-load or light-data re-flag request for a section under the Remix
     * capture rule. The section itself is flagged as vanilla flags it. Each of the eight
     * horizontal neighbours at the same section Y is flagged only if it is resident, has no
     * rebuild pending, and its last region snapshot did not contain the loading column.
     * Vertical neighbours are not visited because every section Y of a loading column
     * receives its own request. A section Y outside the world is ignored.
     */
    void rtxremix$flagLoadedSection(int cx, int sy, int cz);

    /**
     * Records that a compile of the section was scheduled outside the extractor, so the
     * extractor does not schedule the same section again for the same dirty flag.
     */
    void rtxremix$markCompileScheduled(long sectionNode);

    /**
     * Whether the extractor has flagged its compiled geometry for invalidation and has not
     * applied it yet; the view area is replaced when it does.
     */
    boolean rtxremix$compiledGeometryInvalidationPending();

    /**
     * Whether {@link #rtxremix$flagLoadedSection} can apply the rule: the extractor tracks
     * sections, which it does while a level is loaded, and the view area holding the
     * snapshot masks exists. Without either a re-flag must go to vanilla.
     */
    boolean rtxremix$canFilterReflags();
}
