/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Arrays;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;

/**
 * The buffers behind vanilla's visibility state, kept across walks instead of allocated per
 * walk: the per-slot node map, the map of sections waiting for their chunk, the visible
 * {@link PooledOctree} and the {@link ChunkInfoPool}. Two sets of them alternate, because at
 * most two states exist at once: the published one the render thread reads and extends
 * incrementally, and the one a full walk is filling on the visibility thread. Vanilla
 * submits a full walk only from the render thread at the start of a frame and only when the
 * previous walk has published, so when a walk starts refilling a buffer set, the render
 * thread has already moved to the other.
 *
 * <p>{@link #rotate} is called when a full walk begins, before its state is constructed;
 * {@link #configure} when the view area changes, which also drops the old buffers.
 */
public final class VisibilityGraphBuffers {
    /** One state's worth of buffers. */
    public static final class Buffers {
        public final SectionToNodeMap infos;
        public final Long2ObjectOpenHashMap<LongList> waitingForChunkLoads = new Long2ObjectOpenHashMap<>();
        public final ChunkInfoPool infoPool;
        public final PooledOctree octree = new PooledOctree();

        Buffers(int sectionCount) {
            infos = new SectionToNodeMap(Math.max(1, sectionCount));
            infoPool = new ChunkInfoPool(sectionCount);
        }

        void reset() {
            Arrays.fill(infos.nodes, null);
            waitingForChunkLoads.clear();
            infoPool.beginWalk();
        }
    }

    private static Buffers[] sets = new Buffers[0];
    private static int sectionCount;
    private static int current;

    private VisibilityGraphBuffers() {
    }

    /** Sizes the buffers for a view area of {@code newSectionCount} slots; the next state starts from scratch. */
    public static synchronized void configure(int newSectionCount) {
        sectionCount = newSectionCount;
        sets = new Buffers[] {new Buffers(newSectionCount), new Buffers(newSectionCount)};
        current = 0;
    }

    /** Switches to the other buffer set and clears it, for the state a full walk is about to build. */
    public static synchronized void rotate() {
        if (sets.length == 0) {
            configure(sectionCount);
        }
        current ^= 1;
        sets[current].reset();
    }

    /** The buffers the state being constructed should use. */
    public static synchronized Buffers current() {
        if (sets.length == 0) {
            configure(sectionCount);
        }
        return sets[current];
    }
}
