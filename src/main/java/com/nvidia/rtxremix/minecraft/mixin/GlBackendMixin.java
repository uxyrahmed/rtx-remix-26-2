/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.opengl.GlBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * Raises the OpenGL context the window is created with to what the Remix GL
 * interop needs. Since 26.2 the OpenGL backend sets the window hints itself
 * (a 3.3 core context) just before the window is created, so the higher
 * version is requested after it.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {

    @Inject(method = "setWindowHints", at = @At("TAIL"))
    private void rtxremix$requireOpenGl44(CallbackInfo ci) {
        // Core-profile 4.4: the GL interop imports Remix's images and semaphores
        // through EXT_memory_object and EXT_semaphore.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 4);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    }
}
