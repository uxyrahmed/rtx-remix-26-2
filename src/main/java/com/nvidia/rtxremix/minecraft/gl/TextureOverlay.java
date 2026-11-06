/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the Remix output texture as a full-screen, fully opaque quad on
 * Minecraft's main render target, after the world and first-person model have
 * been submitted and before the screen overlays and HUD.
 *
 * <p>Render thread only, on the OpenGL backend: the Remix image is a GL texture
 * from the interop, which Blaze3d cannot bind, so the pass is drawn with raw GL
 * inside a {@link RawGlPass} that keeps Blaze3d's cached state valid.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private static final String VERTEX_SHADER = """
            #version 150
            void main() {
                vec2 position = vec2(
                    float((gl_VertexID << 1) & 2),
                    float(gl_VertexID & 2));
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // Standard GL UVs, with (0,0) at the top left: the interop blit already
    // corrects the Vulkan image's orientation. A fully transparent texel keeps the
    // pixel underneath, as the textured-quad shader it replaces discarded it.
    private static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D RemixColor;
            uniform vec2 FramebufferSize;
            out vec4 fragColor;
            void main() {
                vec2 uv = vec2(
                    gl_FragCoord.x / FramebufferSize.x,
                    1.0 - gl_FragCoord.y / FramebufferSize.y);
                vec4 color = texture(RemixColor, uv);
                if (color.a == 0.0) {
                    discard;
                }
                fragColor = color;
            }
            """;

    private final Minecraft client;
    private final RawGlPass.TargetFramebuffer framebuffer = new RawGlPass.TargetFramebuffer(false);
    private int program;
    private int remixColorLocation;
    private int framebufferSizeLocation;
    private int vertexArray;
    private boolean nonGlTargetLogged;

    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Renders a texture as a full-screen overlay.
     *
     * @param textureId The GL texture ID to render as full-screen overlay
     */
    public void render(int textureId) {
        if (textureId <= 0) {
            return;
        }

        if (client == null || client.getWindow() == null) {
            return;
        }

        try {
            renderTextureOverlay(textureId);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(int texId) {
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture color)) {
            if (!nonGlTargetLogged) {
                LOGGER.error("The main render target is not an OpenGL texture; the Remix image needs the OpenGL backend");
                nonGlTargetLogged = true;
            }
            return;
        }

        try (RawGlPass pass = RawGlPass.begin()) {
            ensureProgram();
            framebuffer.bind(color.glId(), target.width, target.height);
            // Opaque: the Remix image replaces vanilla's output rather than blending
            // with it, and it neither tests nor writes depth.
            pass.resetFixedFunction();
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(remixColorLocation, 0);
            GL20.glUniform2f(framebufferSizeLocation, target.width, target.height);
            pass.bindTexture(0, texId, 0);
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
    }

    private void ensureProgram() {
        if (program != 0) {
            return;
        }
        program = RawGlPass.linkProgram("texture overlay", VERTEX_SHADER, FRAGMENT_SHADER);
        remixColorLocation = GL20.glGetUniformLocation(program, "RemixColor");
        framebufferSizeLocation = GL20.glGetUniformLocation(program, "FramebufferSize");
        vertexArray = GL30.glGenVertexArrays();
    }

    /**
     * Releases the overlay's GL objects. Render thread only.
     */
    public void shutdown() {
        framebuffer.destroy();
        if (vertexArray != 0) {
            GL30.glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        LOGGER.info("TextureOverlay shutdown");
    }
}
