/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.util.thread.BlockableEventLoop;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * Hooks into the Minecraft client's frame and tick.
 *
 * <p>Since 26.1 a frame is {@code runTick} (tasks, client ticks, sound, mouse)
 * followed by {@code renderFrame}: {@code GameRenderer.update} moves the camera,
 * {@code GameRenderer.extract} copies the world into render states,
 * {@code GameRenderer.render} draws, then the window surface blits and presents.
 * Most hooks here only partition that frame into profiling phases; their names
 * are the ones 1.21.1 used, because the profiling tools match on them. The
 * functional hooks are the camera-path pose application before the camera
 * moves, the Remix present fallback, the OpenGL backend preference, and the
 * frame end the benchmark controller times.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    private static final ThreadLocal<ProfileScope> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$renderPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$preGamePrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$beginRenderTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$scheduledTasksScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$soundScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$displayPrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$mouseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$blitScope = new ThreadLocal<>();
    private static final ThreadLocal<Long> rtxremix$renderStartNanos = new ThreadLocal<>();

    /**
     * Tries the OpenGL backend first whenever the Remix runtime is installed.
     * 26.2 can render through Vulkan when the player picks it in Video
     * Settings, but the Remix composite imports Remix's images into OpenGL,
     * so a Vulkan session could never show the path-traced image. Without the
     * runtime, the player's choice stands.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/blaze3d/systems/GpuBackend;"
        )
    )
    private GpuBackend[] rtxremix$preferOpenGlForRemix(PreferredGraphicsApi preferred) {
        if (!Files.isRegularFile(RemixRenderer.runtimeDll(FabricLoader.getInstance().getGameDir()))) {
            return preferred.getBackendsToTry();
        }
        if (preferred == PreferredGraphicsApi.VULKAN) {
            // Own logger: this runs while Minecraft is constructed, before the
            // client entrypoint, so it touches no mod state.
            LoggerFactory.getLogger("rtx-remix-renderer").warn(
                    "RTX Remix composites through OpenGL, so the OpenGL backend is tried "
                    + "before the Vulkan one the graphics API option asks for");
        }
        return new GpuBackend[]{new GlBackend(), new VulkanBackend()};
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void rtxremix$renderHead(boolean advanceGameTime, CallbackInfo ci) {
        ProfileScope.beginFrame();
        rtxremix$renderStartNanos.set(System.nanoTime());
        rtxremix$openRenderScope();
        rtxremix$transitionRenderPhase("MinecraftClient.render.preGameRenderer");
        rtxremix$openScope(rtxremix$preGamePrepScope, "MinecraftClient.render.preGamePrep");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"
        ),
        require = 0
    )
    private void rtxremix$beforeBeginRenderTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$openScope(rtxremix$beginRenderTickScope, "MinecraftClient.render.beginRenderTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBeginRenderTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"
        ),
        require = 0
    )
    private void rtxremix$beforeRunTasks(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        ProfileScope.beginClientTaskDrain("MinecraftClient", rtxremix$getTaskCount());
        rtxremix$openScope(rtxremix$scheduledTasksScope, "MinecraftClient.render.runTasks");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;runAllTasks()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRunTasks(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
    }

    // The texture tick moved from the client tick into runTick in 26.1. It keeps
    // its tick-phase name so recordings stay comparable.
    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeTextureTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.textures");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterTextureTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundListener(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$openScope(rtxremix$soundScope, "MinecraftClient.render.soundListener");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;updateSource(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundListener(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$soundScope);
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"
        ),
        require = 0
    )
    private void rtxremix$beforeMouseTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$mouseScope, "MinecraftClient.render.mouseTick");
    }

    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterMouseTick(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$mouseScope);
    }

    /** Window and surface upkeep, extraction, and everything else before drawing. */
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void rtxremix$beforeDisplayPrep(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$openScope(rtxremix$displayPrepScope, "MinecraftClient.render.displayPrep");
    }

    /**
     * Applies interactive or benchmark camera poses before {@code GameRenderer.update}
     * moves Minecraft's camera for this frame, so the pick, the extracted camera state
     * and the Remix camera all see the pose.
     */
    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void rtxremix$beforeCameraUpdate(boolean advanceGameTime, CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) {
            renderer.onMinecraftWorldRenderFrameStart((Minecraft) (Object) this, advanceGameTime);
        }
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"
        )
    )
    private void rtxremix$beforeGameRenderer(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$closeScope(rtxremix$displayPrepScope);
        rtxremix$transitionRenderPhase("MinecraftClient.render.gameRenderer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterGameRenderer(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.postGameRenderer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeBlit(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeRenderChildScopes();
        rtxremix$openScope(rtxremix$blitScope, "MinecraftClient.render.blitFramebuffer");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBlit(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$blitScope);
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"
        )
    )
    private void rtxremix$beforeSwapBuffers(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.swapBuffers");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterSwapBuffers(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.frameLimit");
    }

    /**
     * Presents through Remix on frames where the window surface did not present.
     * Since 26.2 Minecraft skips the present while the window is minimized or its
     * surface could not be configured, but Remix owns the swapchain whenever the
     * renderer is initialized and every Remix submit is followed by a present, as
     * 1.21.1's unconditional buffer swap guaranteed.
     */
    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V"
        )
    )
    private void rtxremix$presentSkippedFrameThroughRemix(boolean advanceGameTime, CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) {
            renderer.finishFramePresentation();
        }
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"
        ),
        require = 0
    )
    private void rtxremix$beforeLimitDisplayFps(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.limitDisplayFPS");
    }

    @Inject(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterLimitDisplayFps(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.afterFrameLimit");
    }

    // 1.21.1 began this phase after the loop's Thread.yield, which 26.1 dropped.
    // The rest of the frame, the frame counter and runTick's pause bookkeeping, is
    // what it covered.
    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void rtxremix$afterRenderFrame(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.postFrame");
    }

    // Tracy frames come from the runtime's FrameMark inside Present; a second
    // mark here would split every frame at the present boundary.
    @Inject(method = "runTick", at = @At("RETURN"))
    private void rtxremix$renderReturn(boolean advanceGameTime, CallbackInfo ci) {
        rtxremix$closeRenderChildScopes();
        rtxremix$closeRenderPhase();
        rtxremix$closeRenderScope();
        Long startedAt = rtxremix$renderStartNanos.get();
        if (startedAt != null) {
            rtxremix$renderStartNanos.remove();
            long fullFrameNanos = System.nanoTime() - startedAt;
            ProfileScope.emitFrameLedger(fullFrameNanos);
            RemixRenderer renderer = RtxRemixMod.renderer;
            if (renderer != null) {
                renderer.onMinecraftRenderFrameEnd(fullFrameNanos);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$tickHead(CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickScope.set(ProfileScope.begin("MinecraftClient.tick")
                    .plotOnClose("Java.MinecraftClient.tick.ms"));
        }
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeHudTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.hud");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterHudTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V"
        ),
        require = 0
    )
    private void rtxremix$beforeCrosshairTarget(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.crosshairTarget");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pick(F)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterCrosshairTarget(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInteractionTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.interactionManager");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInteractionTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInputEvents(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.inputEvents");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInputEvents(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeGameRendererTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.gameRenderer");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterGameRendererTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    // 1.21.1 also timed LevelRenderer.tick here; 26.1 removed that call from the
    // client tick, so the phase is gone rather than renamed.

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.worldEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.world");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"
        ),
        require = 0
    )
    private void rtxremix$beforeRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.randomBlockDisplay");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeParticleManagerTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.particles");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterParticleManagerTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.sound");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;tick(Z)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeKeyboardTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.keyboard");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/KeyboardHandler;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterKeyboardTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$tickReturn(CallbackInfo ci) {
        rtxremix$closeTickPhase();
        ProfileScope scope = rtxremix$tickScope.get();
        if (scope != null) {
            rtxremix$tickScope.remove();
            scope.close();
        }
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void rtxremix$closeTail(CallbackInfo ci) {
        RtxRemixMod.shutdownAfterMinecraftClose();
    }

    private static void rtxremix$transitionRenderPhase(String phaseName) {
        rtxremix$closeRenderPhase();
        // The phases partition MinecraftClient.render into consecutive spans that
        // give the frame a top-level Tracy skeleton. Injected child scopes close
        // before their phase transitions; when a nested render (world join or
        // disconnect from inside tick) closes a phase under an open child,
        // ProfileScope defers the Tracy end so the zones stay well-formed.
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$renderPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$openRenderScope() {
        rtxremix$closeRenderScope();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$renderScope.set(ProfileScope.beginJfrOnly("MinecraftClient.render")
                    .plotOnClose("Java.MinecraftClient.render.ms"));
        }
    }

    private static void rtxremix$closeRenderScope() {
        ProfileScope scope = rtxremix$renderScope.get();
        if (scope != null) {
            rtxremix$renderScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRenderPhase() {
        ProfileScope scope = rtxremix$renderPhaseScope.get();
        if (scope != null) {
            rtxremix$renderPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openTickPhase(String phaseName) {
        rtxremix$closeTickPhase();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeTickPhase() {
        ProfileScope scope = rtxremix$tickPhaseScope.get();
        if (scope != null) {
            rtxremix$tickPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openScope(ThreadLocal<ProfileScope> holder, String phaseName) {
        rtxremix$closeScope(holder);
        // Guard the begin() call and the plot-name concatenation: these run
        // every frame for each render sub-phase, so skipping them when no Java
        // scope is enabled avoids per-frame string allocation on the hot path.
        if (ProfileScope.isJavaScopeEnabled()) {
            holder.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRenderChildScopes() {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$displayPrepScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$closeScope(rtxremix$blitScope);
    }

    private int rtxremix$getTaskCount() {
        // O(n) in the queued task backlog; skip it when nothing records the value.
        if (!ProfileScope.isClientTaskProfilingEnabled()) {
            return 0;
        }
        return ((BlockableEventLoop<?>)(Object)this).getPendingTasksCount();
    }

    private static void rtxremix$closeClientTaskDrain() {
        Minecraft client = Minecraft.getInstance();
        int taskCount = 0;
        if (client != null && ProfileScope.isClientTaskProfilingEnabled()) {
            taskCount = ((BlockableEventLoop<?>)client).getPendingTasksCount();
        }
        ProfileScope.endClientTaskDrain(taskCount);
    }
}
