/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** The reusable buffers a vanilla visibility state was built on; see {@code RenderableChunksMixin}. */
public interface RenderableChunksBuffers {
    ChunkInfoPool rtxremix$infoPool();

    /**
     * Folds a section a walk added to this state's visible octree into the state's visible-set
     * signature, an order-independent hash of every visible section and their count.
     */
    void rtxremix$recordVisible(SectionRenderDispatcher.RenderSection section);

    long rtxremix$visibleSetHash();

    int rtxremix$visibleSetSize();
}
