/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Running counts of vanilla's terrain layer-group draws and of those the Remix world render
 * skipped, for the profiler's per-phase annotations: a phase reads both at its start and its
 * end. Render thread only.
 */
public final class VanillaTerrainDraws {
    private static int calls;
    private static int skips;

    private VanillaTerrainDraws() {
    }

    /** Records one vanilla terrain layer-group draw, skipped or not. */
    public static void recordCall(boolean skipped) {
        calls++;
        if (skipped) {
            skips++;
        }
    }

    /** Layer-group draws vanilla attempted so far. */
    public static int calls() {
        return calls;
    }

    /** Layer-group draws skipped so far because Remix owns the terrain. */
    public static int skips() {
        return skips;
    }
}
