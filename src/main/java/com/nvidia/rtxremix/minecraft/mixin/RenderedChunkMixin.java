/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A section snapshot for render regions whose block-state copy draws its storage from
 * {@link RenderRegionStoragePool} and gives it back when the last holder lets go. The render
 * region cache that creates the snapshot holds the first reference (released by
 * {@link ChunkRendererRegionBuilderMixin}), and each region built from the cache holds one
 * more until its compile task has run ({@link ChunkRendererRegionMixin},
 * {@link RebuildTaskMixin}); one frame's regions share snapshots, hence the count.
 *
 * <p>The cache is the only creator of section copies, so taking the copy here takes exactly
 * the render snapshot copies from the pool. Vanilla's own copy code is untouched.
 */
@Mixin(SectionCopy.class)
public abstract class RenderedChunkMixin implements RegionSnapshotRelease {

    @Shadow
    @Final
    @Mutable
    private PalettedContainer<BlockState> section;

    /** The pooled storage behind {@link #section}, owned by this snapshot until its last release. */
    private long[] rtxremix$pooledStorage;

    private final AtomicInteger rtxremix$holders = new AtomicInteger();

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/PalettedContainer;copy()Lnet/minecraft/world/level/chunk/PalettedContainer;"))
    private PalettedContainer<BlockState> rtxremix$copyIntoPooledStorage(
            PalettedContainer<BlockState> states, Operation<PalettedContainer<BlockState>> original) {
        RenderRegionStoragePool.beginCopying();
        try {
            return original.call(states);
        } finally {
            rtxremix$pooledStorage = RenderRegionStoragePool.endCopying();
        }
    }

    /** The creating cache's reference. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$retainForCache(LevelChunk levelChunk, int sectionIndex, CallbackInfo ci) {
        rtxremix$holders.incrementAndGet();
    }

    @Override
    public void rtxremix$retain() {
        rtxremix$holders.incrementAndGet();
    }

    @Override
    public void rtxremix$release() {
        if (rtxremix$holders.decrementAndGet() != 0) {
            return;
        }
        long[] storage = rtxremix$pooledStorage;
        rtxremix$pooledStorage = null;
        // Nothing may read the snapshot after its last release. Dropping the container makes a
        // stale read see air, never whatever the recycled array holds next.
        section = null;
        if (storage != null) {
            RenderRegionStoragePool.release(storage);
        }
    }
}
