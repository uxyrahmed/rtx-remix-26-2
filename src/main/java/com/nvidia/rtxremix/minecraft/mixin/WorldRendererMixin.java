/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.CaptureHookStats;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.terrain.VanillaTerrainDraws;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;

/**
 * Brackets vanilla's level render for the Remix frame and labels its phases for
 * Tracy and JFR.
 *
 * <p>Since 26.1 {@code LevelRenderer.render} submits the extracted entities, block
 * entities and particles, prepares their geometry, then runs a frame graph (sky,
 * main, clouds, weather and always-on-top passes) before compiling sections. The
 * start of the method is the last point before anything is captured, so the frame
 * setup the captures depend on runs there; the end marks the scene ready to submit
 * once the first-person hands have been captured. The capture hooks themselves live
 * with their subsystems: terrain, entities, particles, clouds, weather and the sky
 * each target the 26.2 code path that replaced the one this mixin hooked on 1.21.1.
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin implements WorldRendererProfilingAccess {

    @Unique
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Unique
    private ProfileScope rtxremix$profilerPhaseScope;

    @Unique
    private int rtxremix$phaseTerrainCallsStart;

    @Unique
    private int rtxremix$phaseTerrainSkipsStart;

    /**
     * Applies pending world and resource changes, resets the outline capture and opens
     * a fresh entity capture window before vanilla submits anything. A pending texture
     * reload releases retained entity meshes, so it runs before the window opens.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$renderHead(CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$renderScope,
                "WorldRenderer.render",
                "Java.WorldRenderer.render.ms");
        rtxremix$transitionProfilerPhase("preamble");
        RtxRemixMod.onLevelRenderStart();
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) {
            renderer.beginEntityFrame();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$renderReturn(CallbackInfo ci) {
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
        RtxRemixMod.onLevelRenderEnd();
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaProfilerPhase(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    @Unique
    private void rtxremix$transitionProfilerPhase(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$closeProfilerPhase();
            return;
        }
        rtxremix$closeProfilerPhase();
        String phaseName = "WorldRenderer.phase." + rtxremix$sanitizePhase(vanillaPhaseName);
        rtxremix$phaseTerrainCallsStart = VanillaTerrainDraws.calls();
        rtxremix$phaseTerrainSkipsStart = VanillaTerrainDraws.skips();
        rtxremix$profilerPhaseScope = ProfileScope.begin(phaseName)
                .plotOnClose("Java." + phaseName + ".ms");
    }

    @Unique
    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        rtxremix$annotateProfilerPhase(scope);
        scope.close();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    /**
     * Since 26.1 the level renderer keeps no entity counters of its own and empties its
     * render-state lists as it submits them, so the entity counts come from the capture
     * hooks ({@link CaptureHookStats}) and the terrain draw counts from
     * {@link VanillaTerrainDraws}, each as the change within this phase where 1.21.1
     * counted per phase too.
     */
    @Unique
    private void rtxremix$annotateProfilerPhase(ProfileScope scope) {
        if (visibleSections != null) {
            int visibleChunks = visibleSections.size();
            scope.visibleChunkCount(visibleChunks);
            scope.captureCount(visibleChunks);
        }
        scope.entityCount(CaptureHookStats.entityStates());
        scope.blockEntityCount(CaptureHookStats.blockEntityStates());
        if (levelRenderState != null) {
            scope.blockBreakingCount(levelRenderState.blockBreakingRenderStates.size());
        }
        scope.renderLayerCalls(VanillaTerrainDraws.calls() - rtxremix$phaseTerrainCallsStart);
        scope.renderLayerSkips(VanillaTerrainDraws.skips() - rtxremix$phaseTerrainSkipsStart);
    }

    @Unique
    private static String rtxremix$sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }
        return phase.replace(' ', '_');
    }

    @Unique
    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    @Unique
    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
