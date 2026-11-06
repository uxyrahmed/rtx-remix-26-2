/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture reads blocks through the Fabric renderer's own emitter, so it sees the quads
 * Indigo would mesh: every vertex attribute a model or mesh emits, after the model's
 * transforms, with the cull test applied to quads that name a cull face.
 */
@Tag("fabric")
class RemixRenderContextTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** What the sink saw of one quad, copied while the view was valid. */
    private record Captured(
            float[] positions, float[] uvs, int[] colors, int[] lightmaps, boolean[] hasNormal, float[] normalY,
            int tintIndex, int tag, @Nullable Direction cullFace, @Nullable Direction nominalFace) {

        static Captured of(QuadView quad) {
            float[] positions = new float[12];
            float[] uvs = new float[8];
            int[] colors = new int[4];
            int[] lightmaps = new int[4];
            boolean[] hasNormal = new boolean[4];
            float[] normalY = new float[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex * 3] = quad.x(vertex);
                positions[vertex * 3 + 1] = quad.y(vertex);
                positions[vertex * 3 + 2] = quad.z(vertex);
                uvs[vertex * 2] = quad.u(vertex);
                uvs[vertex * 2 + 1] = quad.v(vertex);
                colors[vertex] = quad.color(vertex);
                lightmaps[vertex] = quad.lightmap(vertex);
                hasNormal[vertex] = quad.hasNormal(vertex);
                normalY[vertex] = quad.normalY(vertex);
            }
            return new Captured(positions, uvs, colors, lightmaps, hasNormal, normalY,
                    quad.tintIndex(), quad.tag(), quad.cullFace(), quad.nominalFace());
        }
    }

    private final List<Captured> captured = new ArrayList<>();

    private RemixRenderContext context() {
        RemixRenderContext context = new RemixRenderContext(IndigoRenderer.INSTANCE, quad -> captured.add(Captured.of(quad)));
        context.prepare(null);
        return context;
    }

    @Test
    void meshOutputReachesTheCaptureWithEveryVertexAttributeIntact() {
        MutableMesh builder = IndigoRenderer.INSTANCE.mutableMesh();
        QuadEmitter emitter = builder.emitter();
        emitter.square(Direction.UP, 0, 0, 1, 1, 0);
        for (int vertex = 0; vertex < 4; vertex++) {
            emitter.color(vertex, 0xFF123456 + vertex);
            emitter.uv(vertex, vertex * 0.125f, vertex * 0.25f);
            emitter.lightmap(vertex, 0x00F000A0 + vertex);
            emitter.normal(vertex, 0, 1, 0);
        }
        emitter.tintIndex(3).tag(42).emit();
        Mesh mesh = builder.immutableCopy();
        RemixRenderContext context = context();

        mesh.outputTo(context.emitter());

        assertEquals(1, captured.size());
        Captured quad = captured.getFirst();
        assertArrayEquals(new float[]{0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0}, quad.positions());
        assertArrayEquals(new float[]{0, 0, 0.125f, 0.25f, 0.25f, 0.5f, 0.375f, 0.75f}, quad.uvs());
        for (int vertex = 0; vertex < 4; vertex++) {
            assertEquals(0xFF123456 + vertex, quad.colors()[vertex]);
            assertEquals(0x00F000A0 + vertex, quad.lightmaps()[vertex]);
            assertTrue(quad.hasNormal()[vertex]);
            assertEquals(1.0f, quad.normalY()[vertex]);
        }
        assertEquals(3, quad.tintIndex());
        assertEquals(42, quad.tag());
        assertEquals(Direction.UP, quad.cullFace());
        assertEquals(Direction.UP, quad.nominalFace());
    }

    @Test
    void aCapturedQuadKeepsItsTintIndex() {
        RemixRenderContext context = context();
        context.emitter().square(Direction.UP, 0, 0, 1, 1, 0.5f).tintIndex(2).emit();
        context.emitter().square(Direction.UP, 0, 0, 1, 1, 0.5f).emit();

        assertEquals(2, captured.size());
        assertEquals(2, captured.get(0).tintIndex());
        assertEquals(-1, captured.get(1).tintIndex());
    }

    @Test
    void modelTransformsRunBeforeCaptureAndHiddenCullFacesAreDropped() {
        MutableMesh builder = IndigoRenderer.INSTANCE.mutableMesh();
        for (int tag = 0; tag < 3; tag++) {
            builder.emitter().square(Direction.UP, 0, 0, 1, 1, 0.5f).tag(tag).emit();
        }
        Mesh mesh = builder.immutableCopy();
        RemixRenderContext context = context();
        context.prepare(face -> face == Direction.NORTH);
        QuadEmitter emitter = context.emitter();
        emitter.pushTransform(quad -> {
            // Transforms run in reverse push order: tag 0 has already been rejected.
            assertTrue(quad.tag() > 0);
            if (quad.tag() == 1) {
                quad.cullFace(Direction.NORTH);
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.pos(vertex, quad.x(vertex) + 2, quad.y(vertex), quad.z(vertex));
            }
            return true;
        });
        emitter.pushTransform(quad -> quad.tag() != 0);

        mesh.outputTo(emitter);
        emitter.popTransform();
        emitter.popTransform();

        assertEquals(1, captured.size());
        assertEquals(2, captured.getFirst().tag());
        assertEquals(2.0f, captured.getFirst().positions()[0]);
    }

    @Test
    void aBlockIsEmittedUnderItsCullTestWhichEndsWithTheBlock() {
        RemixRenderContext context = context();
        BlockStateModel model = new TestModel();

        context.emitBlock(model, BlockAndTintGetter.EMPTY, BlockPos.ZERO, null,
                RandomSource.create(0L), face -> face == Direction.UP);

        // The model skipped the hidden face group itself, and the capture dropped the quad
        // whose cull face is hidden.
        assertEquals(2, captured.size());
        assertEquals(Direction.NORTH, captured.get(0).cullFace());
        assertEquals(null, captured.get(1).cullFace());

        captured.clear();
        context.emitter().square(Direction.UP, 0, 0, 1, 1, 0).emit();
        assertEquals(1, captured.size(), "a quad emitted after the block is not culled by the block's test");
    }

    @Test
    void preparingClearsWhatAnAbandonedQuadLeftInTheEmitter() {
        RemixRenderContext context = context();
        context.emitter().normal(0, 1, 0, 0).tag(99).tintIndex(5);

        context.prepare(null);
        context.emitter().square(Direction.UP, 0, 0, 1, 1, 0).emit();

        assertEquals(1, captured.size());
        Captured quad = captured.getFirst();
        assertFalse(quad.hasNormal()[0]);
        assertEquals(0, quad.tag());
        assertEquals(-1, quad.tintIndex());
    }

    /**
     * A model that consults the cull test for its UP group, as a modded model may, and emits a
     * NORTH-culled quad, an UP-culled quad the capture must drop, and an unculled quad.
     */
    private static final class TestModel implements BlockStateModel {
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        }

        @Override
        public Material.Baked particleMaterial() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int materialFlags() {
            return 0;
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, @Nullable BlockState state,
                              RandomSource random, Predicate<@Nullable Direction> cullTest) {
            assertTrue(cullTest.test(Direction.UP));
            assertFalse(cullTest.test(Direction.NORTH));
            assertFalse(cullTest.test(null));
            emitter.square(Direction.NORTH, 0, 0, 1, 1, 0).emit();
            emitter.square(Direction.UP, 0, 0, 1, 1, 0).emit();
            emitter.square(Direction.SOUTH, 0, 0, 1, 1, 0.5f).emit();
        }
    }
}
