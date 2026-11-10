/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Bracket for drawing with raw OpenGL between Blaze3d's own passes on the OpenGL
 * backend, for draws that sample textures Blaze3d does not own, such as the Remix
 * interop images.
 *
 * <p>Blaze3d caches GL state and trusts the cache. {@link GlStateManager} tracks
 * capabilities, depth, blend and colour-mask state, the active texture unit, the
 * texture bound to each of its first units and the bound framebuffers; the command
 * encoder remembers the program and vertex array it last bound. A raw pass therefore
 * changes the tracked state only through {@code GlStateManager}, so the cache stays
 * true and the next pipeline applies what it needs, and on {@link #close()} restores
 * the untracked state it found: program, vertex array, array buffer, framebuffers,
 * viewport and the sampler objects of the units it used.
 *
 * <p>Render thread only.
 */
public final class RawGlPass implements AutoCloseable {

    private static final int MAX_TRACKED_UNITS = 12;

    private final int program;
    private final int vertexArray;
    private final int arrayBuffer;
    private final int readFramebuffer;
    private final int drawFramebuffer;
    private final int[] viewport = new int[4];
    private int usedUnits;

    private RawGlPass() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        readFramebuffer = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        drawFramebuffer = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    }

    /** Records the untracked state a raw pass is about to change. */
    public static RawGlPass begin() {
        return new RawGlPass();
    }

    /**
     * Sets the fixed-function state of an unblended, untested full-target pass. Blend,
     * depth and colour-mask state go through {@code GlStateManager}; a caller that blends
     * or tests depth enables it after this.
     */
    public void resetFixedFunction() {
        GlStateManager._disableBlend(0);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();
        GlStateManager._disablePolygonOffset();
        GlStateManager._disableColorLogicOp();
        GlStateManager._colorMask(15);
        // Every pipeline sets its own polygon mode, so filling here needs no restore.
        GlStateManager._polygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }

    /**
     * Binds a 2D texture to a unit for the pass. A sampler of 0 samples with the
     * texture's own parameters; Blaze3d's textures carry their filtering in sampler
     * objects instead, so pass a sampler when binding one of those.
     */
    public void bindTexture(int unit, int texture, int sampler) {
        if (unit < 0 || unit >= MAX_TRACKED_UNITS) {
            throw new IllegalArgumentException("Texture unit " + unit + " is outside the tracked units");
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL33.glBindSampler(unit, sampler);
        usedUnits |= 1 << unit;
    }

    @Override
    public void close() {
        for (int unit = 0; unit < MAX_TRACKED_UNITS; unit++) {
            if ((usedUnits & (1 << unit)) != 0) {
                GL33.glBindSampler(unit, 0);
            }
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /**
     * Whether clip-space depth runs from 0 to 1. Blaze3d switches the context to it
     * when the driver supports clip control, so a perspective projection written for
     * the classic -1 to 1 range must be built for 0 to 1 instead to land at the same
     * window depths.
     */
    public static boolean isClipDepthZeroToOne() {
        GLCapabilities capabilities = GL.getCapabilities();
        return (capabilities.OpenGL45 || capabilities.GL_ARB_clip_control)
                && GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) == GL45.GL_ZERO_TO_ONE;
    }

    /**
     * Compiles and links a vertex and fragment shader pair, binding each named vertex
     * attribute to the location of its position in {@code attributes}.
     *
     * @throws IllegalStateException with the driver's log when compiling or linking fails
     */
    public static int linkProgram(String name, String vertexSource, String fragmentSource,
                                  String... attributes) {
        int vertexShader = compileShader(name, GL20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = 0;
        try {
            fragmentShader = compileShader(name, GL20.GL_FRAGMENT_SHADER, fragmentSource);
            int linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertexShader);
            GL20.glAttachShader(linked, fragmentShader);
            for (int location = 0; location < attributes.length; location++) {
                GL20.glBindAttribLocation(linked, location, attributes[location]);
            }
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(linked);
                GL20.glDeleteProgram(linked);
                throw new IllegalStateException("Failed to link " + name + " shader: " + log);
            }
            return linked;
        } finally {
            GL20.glDeleteShader(vertexShader);
            if (fragmentShader != 0) {
                GL20.glDeleteShader(fragmentShader);
            }
        }
    }

    private static int compileShader(String name, int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile " + name + " shader: " + log);
        }
        return shader;
    }

    /** Creates a sampler object; Blaze3d's textures are sampled through one of these. */
    public static int createSampler(int filter, int wrap) {
        int sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, wrap);
        return sampler;
    }

    /**
     * A framebuffer that draws into a colour texture it does not own, such as the main
     * render target's, with an optional depth buffer of its own so a pass can depth-test
     * without touching the target's depth.
     */
    public static final class TargetFramebuffer {
        private final boolean ownDepth;
        private int framebuffer;
        private int depthRenderbuffer;
        private int depthWidth;
        private int depthHeight;

        public TargetFramebuffer(boolean ownDepth) {
            this.ownDepth = ownDepth;
        }

        /**
         * Binds the framebuffer for drawing into {@code colorTexture} and sets the
         * viewport to the whole target. The colour attachment is refreshed on every bind
         * because the target recreates its textures when it resizes.
         *
         * @throws IllegalStateException if the driver reports the framebuffer incomplete
         */
        public void bind(int colorTexture, int width, int height) {
            if (framebuffer == 0) {
                framebuffer = GlStateManager.glGenFramebuffers();
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, colorTexture, 0);
            if (ownDepth && (depthRenderbuffer == 0 || depthWidth != width || depthHeight != height)) {
                if (depthRenderbuffer == 0) {
                    depthRenderbuffer = GL30.glGenRenderbuffers();
                }
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRenderbuffer);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT32F, width, height);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_RENDERBUFFER, depthRenderbuffer);
                depthWidth = width;
                depthHeight = height;
            }
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Overlay framebuffer is incomplete: 0x" + Integer.toHexString(status));
            }
            GlStateManager._viewport(0, 0, width, height);
        }

        /** Deletes the framebuffer and its depth buffer; the colour texture stays its owner's. */
        public void destroy() {
            if (framebuffer != 0) {
                GlStateManager._glDeleteFramebuffers(framebuffer);
                framebuffer = 0;
            }
            if (depthRenderbuffer != 0) {
                GL30.glDeleteRenderbuffers(depthRenderbuffer);
                depthRenderbuffer = 0;
            }
            depthWidth = 0;
            depthHeight = 0;
        }
    }
}
