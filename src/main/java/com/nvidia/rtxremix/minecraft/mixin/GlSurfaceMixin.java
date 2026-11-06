/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.opengl.GlSurface;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the OpenGL backend's buffer swap to Remix's present, so Vulkan owns
 * the swapchain whenever the renderer is initialized. Before that, and if
 * initialization fails, the GLFW swap runs unchanged.
 *
 * <p>On 1.21.1 the swap lived in {@code RenderSystem.flipFrame}. Since 26.2 the
 * game presents through a {@code GpuSurface}, whose OpenGL implementation swaps
 * in {@link GlSurface#present()}; the Vulkan backend never reaches this class,
 * which is why {@link MinecraftClientMixin} prefers OpenGL while the Remix
 * runtime is installed. Frames on which the surface does not present at all
 * are presented by {@code RemixRenderer.finishFramePresentation}.
 */
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {

    @Redirect(
        method = "present",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V")
    )
    private void rtxremix$redirectSwapBuffers(long window) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
        } else {
            GLFW.glfwSwapBuffers(window);
        }
    }
}
