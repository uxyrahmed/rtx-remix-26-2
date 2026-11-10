/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * A render region cache's reference on the section snapshots it cached. The cache holds one
 * reference per snapshot from creation until its caller is done scheduling with it, so a
 * snapshot handed to several regions cannot be recycled by an early task while a later region
 * is still being assembled from the same cache.
 */
public interface RegionBuilderRelease {
    /** Drops the cache's reference on every snapshot it created. Call once, when done with the cache. */
    void rtxremix$releaseSnapshots();
}
