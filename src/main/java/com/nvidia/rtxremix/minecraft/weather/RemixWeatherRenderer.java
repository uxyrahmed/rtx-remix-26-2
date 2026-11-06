/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.gl.RawGlPass;
import com.nvidia.rtxremix.minecraft.mixin.WeatherEffectRendererAccessor;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Draws rain and snow with GL directly onto the composited Remix colour image,
 * depth-testing against the interop depth texture so weather is occluded by
 * path-traced geometry.
 *
 * <p>Vanilla's weather pass is captured rather than drawn while Remix draws the
 * world ({@code WeatherEffectRendererMixin}): its rain and snow columns are built
 * into camera-relative quads by vanilla's own column builder and kept until Remix
 * has presented its image into GL. Rain then draws with the refraction shader,
 * which bends the Remix image behind each streak, and snow with a lightmapped
 * particle shader; both are this mod's shaders, read from its resources and
 * rebuilt when resources reload.
 *
 * <p>Render thread only, on the OpenGL backend: the interop textures are GL
 * textures Blaze3d cannot bind, so the pass is raw GL inside a {@link RawGlPass}.
 * The Remix depth is seeded into a depth buffer of the pass's own rather than the
 * main render target's, which keeps vanilla's depth untouched.
 */
public final class RemixWeatherRenderer {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final Identifier RAIN_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/snow.png");
    private static final String RAIN_SHADER = "shaders/core/rain_refraction";
    private static final String SNOW_SHADER = "shaders/core/weather_snow";
    private static final Identifier SHADER_RELOAD_LISTENER =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "weather_shaders");
    /** Vanilla's weather pass fades rain from full alpha and snow from 0.8 toward the radius. */
    private static final float RAIN_MAX_ALPHA = 1.0f;
    private static final float SNOW_MAX_ALPHA = 0.8f;
    private static final String[] PARTICLE_ATTRIBUTES = {"Position", "UV0", "Color", "UV2"};
    private static final int VERTEX_STRIDE = DefaultVertexFormat.PARTICLE.getVertexSize();
    private static final int INITIAL_BUILDER_BYTES = 1024 * 4 * VERTEX_STRIDE;

    /** Advanced by every resource reload; programs built under an older value are rebuilt. */
    private static volatile int shaderGeneration;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Quaternionf viewRotation = new Quaternionf();
    private final float[] matrixUpload = new float[16];
    private boolean cameraKnown;
    private float fovYDegrees;
    private float aspect;
    private ByteBufferBuilder rainBytes;
    private ByteBufferBuilder snowBytes;
    private MeshData rainMesh;
    private MeshData snowMesh;
    private GlResources glResources;
    private int capturedQuadCount;
    private int lastDrawCount;
    private int lastQuadCount;

    /**
     * Rebuilds the weather shaders whenever client resources reload, as vanilla
     * reloads its core shaders, so a resource pack can replace them.
     */
    public static void registerShaders() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                SHADER_RELOAD_LISTENER,
                (ResourceManagerReloadListener) manager -> shaderGeneration++);
    }

    /**
     * Captures the weather vanilla's pass would draw this frame: rain and snow quads
     * built by vanilla's column builder, relative to {@code cameraPos}.
     */
    public void capture(WeatherEffectRenderer vanilla, Vec3 cameraPos, WeatherRenderState state) {
        closePendingDraws();
        capturedQuadCount = 0;
        lastDrawCount = 0;
        lastQuadCount = 0;
        WeatherEffectRendererAccessor builder = (WeatherEffectRendererAccessor) vanilla;
        if (!state.rainColumns.isEmpty()) {
            if (rainBytes == null) rainBytes = new ByteBufferBuilder(INITIAL_BUILDER_BYTES);
            rainMesh = buildColumns(builder, rainBytes, state.rainColumns, cameraPos,
                    RAIN_MAX_ALPHA, state.radius, state.intensity);
        }
        if (!state.snowColumns.isEmpty()) {
            if (snowBytes == null) snowBytes = new ByteBufferBuilder(INITIAL_BUILDER_BYTES);
            snowMesh = buildColumns(builder, snowBytes, state.snowColumns, cameraPos,
                    SNOW_MAX_ALPHA, state.radius, state.intensity);
        }
        capturedQuadCount = quadCount(rainMesh) + quadCount(snowMesh);
    }

    private static MeshData buildColumns(WeatherEffectRendererAccessor builder, ByteBufferBuilder bytes,
                                         List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos,
                                         float maxAlpha, int radius, float intensity) {
        BufferBuilder quads = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.PARTICLE);
        builder.invokeRenderInstances(quads, columns, cameraPos, maxAlpha, radius, intensity);
        return quads.build();
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        camera.rotation().conjugate(viewRotation);
        this.fovYDegrees = fovYDegrees;
        this.aspect = aspect;
        cameraKnown = true;
    }

    public void render(int finalColorTexture, int depthTexture) {
        if (rainMesh == null && snowMesh == null) {
            return;
        }
        try {
            if (depthTexture == 0) {
                throw new IllegalStateException("Remix depth texture is unavailable");
            }
            if (finalColorTexture == 0) {
                throw new IllegalStateException("Remix final-color texture is unavailable");
            }
            if (!cameraKnown) {
                throw new IllegalStateException("Weather camera state was not initialized");
            }
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (!(target.getColorTexture() instanceof GlTexture color)) {
                throw new IllegalStateException("Weather compositing needs the OpenGL backend");
            }
            lastDrawCount = (rainMesh != null ? 1 : 0) + (snowMesh != null ? 1 : 0);
            lastQuadCount = capturedQuadCount;
            drawCapturedWeather(color.glId(), target.width, target.height, finalColorTexture, depthTexture);
        } finally {
            closePendingDraws();
        }
    }

    private void drawCapturedWeather(int colorTexture, int width, int height,
                                     int finalColorTexture, int depthTexture) {
        modelView.rotation(viewRotation);
        projection.setPerspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, FAR_PLANE,
                RawGlPass.isClipDepthZeroToOne());

        try (RawGlPass pass = RawGlPass.begin()) {
            GlResources gl = glResources();
            gl.framebuffer.bind(colorTexture, width, height);
            pass.resetFixedFunction();
            gl.seedDepth(pass, depthTexture, width, height);

            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ZERO);
            GlStateManager._blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(false);
            GlStateManager._colorMask(15);

            int rainVertices = vertexCount(rainMesh);
            gl.uploadVertices(rainMesh, snowMesh, rainVertices + vertexCount(snowMesh));
            gl.ensureQuadIndices(Math.max(quadCount(rainMesh), quadCount(snowMesh)));
            GlStateManager._glBindVertexArray(gl.vertexArray);

            if (rainMesh != null) {
                useWeatherProgram(gl.rain);
                pass.bindTexture(0, textureId(RAIN_TEXTURE), gl.weatherSampler);
                pass.bindTexture(1, finalColorTexture, 0);
                GL20.glUniform1i(gl.rain.sampler1, 1);
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, quadCount(rainMesh) * 6,
                        GL11.GL_UNSIGNED_INT, 0L, 0);
            }
            if (snowMesh != null) {
                useWeatherProgram(gl.snow);
                pass.bindTexture(0, textureId(SNOW_TEXTURE), gl.weatherSampler);
                pass.bindTexture(2, lightmapTextureId(), gl.lightmapSampler);
                GL20.glUniform1i(gl.snow.sampler2, 2);
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, quadCount(snowMesh) * 6,
                        GL11.GL_UNSIGNED_INT, 0L, rainVertices);
            }
        }
    }

    private void useWeatherProgram(WeatherProgram program) {
        GlStateManager._glUseProgram(program.program);
        projection.get(matrixUpload);
        GL20.glUniformMatrix4fv(program.projMat, false, matrixUpload);
        modelView.get(matrixUpload);
        GL20.glUniformMatrix4fv(program.modelViewMat, false, matrixUpload);
        GL20.glUniform4f(program.colorModulator, 1.0f, 1.0f, 1.0f, 1.0f);
        GL20.glUniform1i(program.sampler0, 0);
    }

    private GlResources glResources() {
        if (glResources == null) {
            glResources = new GlResources();
        }
        glResources.refreshPrograms();
        return glResources;
    }

    private static int textureId(Identifier location) {
        GpuTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location).getTexture();
        return ((GlTexture) texture).glId();
    }

    private static int lightmapTextureId() {
        return ((GlTexture) Minecraft.getInstance().gameRenderer.lightmap().texture()).glId();
    }

    private static int vertexCount(MeshData mesh) {
        return mesh != null ? mesh.drawState().vertexCount() : 0;
    }

    private static int quadCount(MeshData mesh) {
        return vertexCount(mesh) / 4;
    }

    private void closePendingDraws() {
        if (rainMesh != null) {
            rainMesh.close();
            rainMesh = null;
        }
        if (snowMesh != null) {
            snowMesh.close();
            snowMesh = null;
        }
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    public void cleanup() {
        closePendingDraws();
        if (rainBytes != null) {
            rainBytes.close();
            rainBytes = null;
        }
        if (snowBytes != null) {
            snowBytes.close();
            snowBytes = null;
        }
        if (glResources != null) {
            glResources.destroy();
            glResources = null;
        }
    }

    private static String loadShaderSource(ResourceManager resources, String path, String extension) {
        Identifier location = Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, path + extension);
        Resource resource = resources.getResource(location)
                .orElseThrow(() -> new IllegalStateException("Missing weather shader " + location));
        try (InputStream stream = resource.open()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read weather shader " + location, e);
        }
    }

    /** One linked weather program and the uniforms the pass sets on it. */
    private static final class WeatherProgram {
        final int program;
        final int modelViewMat;
        final int projMat;
        final int colorModulator;
        final int sampler0;
        final int sampler1;
        final int sampler2;

        WeatherProgram(ResourceManager resources, String path) {
            program = RawGlPass.linkProgram(path,
                    loadShaderSource(resources, path, ".vsh"),
                    loadShaderSource(resources, path, ".fsh"),
                    PARTICLE_ATTRIBUTES);
            modelViewMat = GL20.glGetUniformLocation(program, "ModelViewMat");
            projMat = GL20.glGetUniformLocation(program, "ProjMat");
            colorModulator = GL20.glGetUniformLocation(program, "ColorModulator");
            sampler0 = GL20.glGetUniformLocation(program, "Sampler0");
            sampler1 = GL20.glGetUniformLocation(program, "Sampler1");
            sampler2 = GL20.glGetUniformLocation(program, "Sampler2");
        }

        void destroy() {
            GL20.glDeleteProgram(program);
        }
    }

    /** The GL objects of the weather pass, created on the first frame with weather. */
    private static final class GlResources {
        final RawGlPass.TargetFramebuffer framebuffer = new RawGlPass.TargetFramebuffer(true);
        final DepthSeeder depthSeeder = new DepthSeeder();
        final int weatherSampler = RawGlPass.createSampler(GL11.GL_NEAREST, GL11.GL_REPEAT);
        final int lightmapSampler = RawGlPass.createSampler(GL11.GL_LINEAR, GL12.GL_CLAMP_TO_EDGE);
        final int vertexArray;
        final int vertexBuffer;
        final int indexBuffer;
        int vertexCapacityBytes;
        int indexCapacityQuads;
        WeatherProgram rain;
        WeatherProgram snow;
        int programGeneration = -1;

        GlResources() {
            vertexArray = GL30.glGenVertexArrays();
            vertexBuffer = GL15.glGenBuffers();
            indexBuffer = GL15.glGenBuffers();
            // Bound inside a raw pass, which restores the vertex array and array buffer.
            GlStateManager._glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 12L);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE, 20L);
            GL20.glEnableVertexAttribArray(3);
            GL30.glVertexAttribIPointer(3, 2, GL11.GL_SHORT, VERTEX_STRIDE, 24L);
        }

        /** Builds the programs, or rebuilds them from the current resources after a reload. */
        void refreshPrograms() {
            int generation = shaderGeneration;
            if (generation == programGeneration) {
                return;
            }
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            WeatherProgram newRain = new WeatherProgram(resources, RAIN_SHADER);
            WeatherProgram newSnow;
            try {
                newSnow = new WeatherProgram(resources, SNOW_SHADER);
            } catch (RuntimeException e) {
                newRain.destroy();
                throw e;
            }
            destroyPrograms();
            rain = newRain;
            snow = newSnow;
            programGeneration = generation;
        }

        void seedDepth(RawGlPass pass, int depthTexture, int width, int height) {
            depthSeeder.seed(pass, depthTexture, width, height);
        }

        /** Uploads rain then snow into one vertex buffer, which snow draws from with a base vertex. */
        void uploadVertices(MeshData rainMesh, MeshData snowMesh, int vertexCount) {
            int bytes = vertexCount * VERTEX_STRIDE;
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            if (bytes > vertexCapacityBytes) {
                // Re-specified only to grow: the NVIDIA driver never reclaims an orphaned
                // allocation under the GL-Vulkan interop.
                vertexCapacityBytes = bytes + bytes / 2;
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexCapacityBytes, GL15.GL_DYNAMIC_DRAW);
            }
            long offset = 0L;
            if (rainMesh != null) {
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, rainMesh.vertexBuffer());
                offset += (long) rainMesh.drawState().vertexCount() * VERTEX_STRIDE;
            }
            if (snowMesh != null) {
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, snowMesh.vertexBuffer());
            }
        }

        /** Grows the quad index buffer, two triangles per quad in vanilla's 0-1-2, 2-3-0 order. */
        void ensureQuadIndices(int quads) {
            if (quads <= indexCapacityQuads) {
                return;
            }
            int capacity = quads + quads / 2;
            IntBuffer indices = MemoryUtil.memAllocInt(capacity * 6);
            try {
                for (int quad = 0; quad < capacity; quad++) {
                    int vertex = quad * 4;
                    indices.put(vertex).put(vertex + 1).put(vertex + 2)
                            .put(vertex + 2).put(vertex + 3).put(vertex);
                }
                indices.flip();
                // The element buffer is vertex array state, so it is bound through the vertex array.
                GlStateManager._glBindVertexArray(vertexArray);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(indices);
            }
            indexCapacityQuads = capacity;
        }

        private void destroyPrograms() {
            if (rain != null) {
                rain.destroy();
                rain = null;
            }
            if (snow != null) {
                snow.destroy();
                snow = null;
            }
        }

        void destroy() {
            destroyPrograms();
            depthSeeder.destroy();
            framebuffer.destroy();
            GL30.glDeleteVertexArrays(vertexArray);
            GL15.glDeleteBuffers(vertexBuffer);
            GL15.glDeleteBuffers(indexBuffer);
            GL33.glDeleteSamplers(weatherSampler);
            GL33.glDeleteSamplers(lightmapSampler);
        }
    }

    /** Writes the Remix depth into the pass's depth buffer, so weather tests against path-traced depth. */
    private static final class DepthSeeder {
        private static final String VERTEX_SHADER = """
                #version 150
                void main() {
                    vec2 position = vec2(
                        float((gl_VertexID << 1) & 2),
                        float(gl_VertexID & 2));
                    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                }
                """;

        private static final String FRAGMENT_SHADER = """
                #version 150
                uniform sampler2D RemixDepth;
                uniform vec2 FramebufferSize;
                void main() {
                    vec2 uv = vec2(
                        gl_FragCoord.x / FramebufferSize.x,
                        1.0 - gl_FragCoord.y / FramebufferSize.y);
                    gl_FragDepth = texture(RemixDepth, uv).r;
                }
                """;

        private final int program;
        private final int vertexArray;
        private final int remixDepthLocation;
        private final int framebufferSizeLocation;

        private DepthSeeder() {
            program = RawGlPass.linkProgram("depth seeding", VERTEX_SHADER, FRAGMENT_SHADER);
            remixDepthLocation = GL20.glGetUniformLocation(program, "RemixDepth");
            framebufferSizeLocation = GL20.glGetUniformLocation(program, "FramebufferSize");
            vertexArray = GL30.glGenVertexArrays();
        }

        private void seed(RawGlPass pass, int depthTexture, int width, int height) {
            // OpenGL suppresses depth writes entirely when the depth test is disabled.
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_ALWAYS);
            GlStateManager._depthMask(true);
            GlStateManager._colorMask(0);
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(remixDepthLocation, 0);
            GL20.glUniform2f(framebufferSizeLocation, width, height);
            pass.bindTexture(0, depthTexture, 0);
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);
        }

        private void destroy() {
            GL30.glDeleteVertexArrays(vertexArray);
            GL20.glDeleteProgram(program);
        }
    }
}
