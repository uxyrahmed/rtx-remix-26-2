/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * How many entity and block-entity render states vanilla submitted in the latest level
 * render, counted by the in-place capture hooks for the profiler's phase annotations.
 *
 * <p>On 1.21.1 the profiler read {@code LevelRenderer}'s own rendered-entity counters.
 * 26.x keeps only the extracted entity count ({@code LevelRenderState.lastEntityRenderStateCount})
 * and clears the state lists as it submits them, so the hooks count what passes through
 * them. Render thread only. The counts restart when {@code LevelRenderer.submitFeatures}
 * begins and stay readable until the next one does.
 */
public final class CaptureHookStats {

    private static int entityStates;
    private static int blockEntityStates;

    private CaptureHookStats() {}

    /** Called as a level render starts submitting its features. */
    public static void beginLevelSubmit() {
        entityStates = 0;
        blockEntityStates = 0;
    }

    public static void noteEntitySubmitted() {
        entityStates++;
    }

    public static void noteBlockEntitySubmitted() {
        blockEntityStates++;
    }

    /** Entity render states the latest level render submitted, captured or not. */
    public static int entityStates() {
        return entityStates;
    }

    /** Block-entity render states the latest level render submitted, captured or not. */
    public static int blockEntityStates() {
        return blockEntityStates;
    }
}
