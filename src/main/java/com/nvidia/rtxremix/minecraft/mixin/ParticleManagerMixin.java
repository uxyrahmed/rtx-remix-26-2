/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Coarse particle ticking markers, and the switch that hands particle drawing
 * to Remix. The markers intentionally avoid per-particle zones because the
 * benchmark scene can tick thousands of particles per frame; the per-group
 * zone is {@code ParticleGroupMixin}.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rtxremix$tickHead(CallbackInfo ci) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeGroups = 0;
        for (ParticleGroup<?> group : particles.values()) {
            int size = group.size();
            activeParticles += size;
            if (size > 0) activeGroups++;
        }
        rtxremix$closeScope();
        rtxremix$tickScope.set(ProfileScope.beginJfrOnly("ParticleManager.tick")
                .captureCount(activeParticles)
                .layerCount(activeGroups)
                .plotOnClose("Java.ParticleManager.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void rtxremix$tickReturn(CallbackInfo ci) {
        ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
        ProfileScope tickScope = rtxremix$tickScope.get();
        if (tickScope != null) {
            tickScope.collisionChecks(stats.collisionChecks())
                    .collisionSkips(stats.skippedCollisionChecks())
                    .collisionNanos(stats.collisionNanos());
            ProfileScope.plot("Java.ParticleManager.collisionChecks", stats.collisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionSkips", stats.skippedCollisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionMs", stats.collisionNanos() / 1_000_000.0);
        }
        rtxremix$closeScope();
    }

    /**
     * Remix draws particles from its own capture of the live groups
     * ({@code RemixParticleRenderer}), so vanilla's per-frame extraction, and
     * with it every particle submit that would follow, is skipped while Remix
     * draws the world. Extraction only fills the level render state, which the
     * level render resets every frame, so skipping it changes no other state.
     */
    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaParticleExtraction(
            ParticlesRenderState particlesRenderState,
            Frustum frustum,
            Camera camera,
            float partialTickTime,
            CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            ci.cancel();
        }
    }

    private static void rtxremix$closeScope() {
        ProfileScope scope = rtxremix$tickScope.get();
        if (scope != null) {
            rtxremix$tickScope.remove();
            scope.close();
        }
    }
}
