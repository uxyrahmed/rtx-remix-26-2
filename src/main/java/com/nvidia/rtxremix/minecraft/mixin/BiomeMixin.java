/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers a biome's foliage and dry foliage colours from a cached value instead of the
 * {@code Optional.orElseGet(this::...FromTexture)} vanilla runs on every call, which allocates
 * a bound method reference and boxes a freshly computed value. The client level's tint cache
 * asks for these for each blend-radius sample of each block position it fills, on the
 * section-build workers, and the boxed integers were the largest single class of garbage left
 * on those threads.
 *
 * <p>Both values are per-biome constants for a given pair of colormaps, so vanilla's first
 * answer is kept, stamped with the {@link BiomeColorGeneration} it was computed under; a
 * resource reload that replaces a colormap moves the generation on and the next lookup
 * recomputes ({@link FoliageColorsMixin}). The grass colour needs no cache: vanilla reads its
 * override without a method reference or a fresh box, so its path allocates nothing.
 *
 * <p>What is cached is the boxed {@link Integer} vanilla's path produced, and that same object
 * is handed back on every hit: {@code setReturnValue(int)} would box again per call, which is
 * what the first version of this mixin did (0.67 GB of Integer over a benchmark run). Each
 * cache is one volatile reference to an immutable holder, so a worker that sees the holder sees
 * a colour and the generation it belongs to together.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    private volatile BiomeColorGeneration.Cached rtxremix$foliage;
    private volatile BiomeColorGeneration.Cached rtxremix$dryFoliage;

    @Inject(method = "getFoliageColor", at = @At("HEAD"), cancellable = true)
    private void rtxremix$foliageColor(CallbackInfoReturnable<Integer> cir) {
        BiomeColorGeneration.Cached cached = rtxremix$foliage;
        if (cached != null && cached.isCurrent()) {
            cir.setReturnValue(cached.color());
        }
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"))
    private void rtxremix$rememberFoliageColor(CallbackInfoReturnable<Integer> cir) {
        rtxremix$foliage = new BiomeColorGeneration.Cached(BiomeColorGeneration.current(), cir.getReturnValue());
    }

    @Inject(method = "getDryFoliageColor", at = @At("HEAD"), cancellable = true)
    private void rtxremix$dryFoliageColor(CallbackInfoReturnable<Integer> cir) {
        BiomeColorGeneration.Cached cached = rtxremix$dryFoliage;
        if (cached != null && cached.isCurrent()) {
            cir.setReturnValue(cached.color());
        }
    }

    @Inject(method = "getDryFoliageColor", at = @At("RETURN"))
    private void rtxremix$rememberDryFoliageColor(CallbackInfoReturnable<Integer> cir) {
        rtxremix$dryFoliage = new BiomeColorGeneration.Cached(BiomeColorGeneration.current(), cir.getReturnValue());
    }
}
