/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/**
 * Diagnostics switches of the visibility walk port, read once at startup.
 *
 * <p>{@code -Drtxremix.vanillaVisibilityWalk=true} leaves the visibility walk and its visible
 * octree to vanilla with RTX on. The port must produce vanilla's visible set exactly; this is
 * how to check that on a live world, together with {@code -Drtxremix.logVisibleSet=true},
 * which logs the size and a position hash of the visible set whenever a walk changes it. Run
 * the same camera with and without the first switch and compare the last line each logged.
 */
public final class VisibilityWalkSwitches {
    public static final boolean VANILLA_WALK = Boolean.getBoolean("rtxremix.vanillaVisibilityWalk");
    public static final boolean LOG_VISIBLE_SET = Boolean.getBoolean("rtxremix.logVisibleSet");

    private VisibilityWalkSwitches() {
    }
}
