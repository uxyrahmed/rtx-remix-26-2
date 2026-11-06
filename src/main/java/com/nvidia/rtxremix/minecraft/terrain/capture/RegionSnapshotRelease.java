/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * Ownership of a render region snapshot's pooled storage. A region retains each section
 * snapshot it includes; the compile task that consumed the region releases it once, after
 * the section compile, and a snapshot returns its array to {@link RenderRegionStoragePool}
 * when its last holder lets go.
 */
public interface RegionSnapshotRelease {
    /** Called by a region on each section snapshot it takes a reference to. */
    void rtxremix$retain();

    /** Drops one reference; recycles the storage on the last one. Idempotent per holder. */
    void rtxremix$release();
}
