/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Captures the quads a block model emits through Fabric's renderer API.
 *
 * <p>Every block model is a {@code FabricBlockStateModel}, and Fabric documents
 * {@code emitQuads} as the only accurate way to read one: vanilla models adapt their baked
 * parts into it, and a mod's model may produce geometry {@code collectParts} never shows. The
 * capture therefore reads every block, vanilla or modded, through this one path, which is also
 * the path Indigo meshes terrain through.
 *
 * <p>The emitter is the active renderer's own ({@link Renderer#quadEmitter}), so a mesh a model
 * outputs into it takes the renderer's own copy, and nothing here re-implements the quad
 * encoding. Each emitted quad reaches the {@link QuadSink} after the model's transforms, unless
 * it names a cull face the block's neighbour hides; that is the drop Indigo's block renderer
 * makes, while a model can also consult the same test to skip whole face groups.
 *
 * <p>One instance per {@link BlockQuadEmitter}, therefore per worker thread. The emitter is
 * reused for every block and nothing is allocated per quad.
 */
final class RemixRenderContext implements Consumer<MutableQuadView> {

    /** Receives each captured quad. The view is valid only for the duration of the call. */
    @FunctionalInterface
    interface QuadSink {
        void quad(QuadView quad);
    }

    private static final Predicate<@Nullable Direction> NO_CULL = face -> false;

    private final QuadEmitter emitter;
    private final QuadSink sink;
    private Predicate<@Nullable Direction> cullTest = NO_CULL;

    /** A context on the active renderer's emitter. */
    RemixRenderContext(QuadSink sink) {
        this(Renderer.get(), sink);
    }

    RemixRenderContext(Renderer renderer, QuadSink sink) {
        this.sink = sink;
        this.emitter = renderer.quadEmitter(this);
    }

    /** The emitter quads are captured from; a model's {@code emitQuads} writes into it. */
    QuadEmitter emitter() {
        return emitter;
    }

    /**
     * Emits one block's geometry into the sink.
     *
     * @param random   seeded by the caller with the block's rendering seed, as vanilla seeds it
     * @param cullTest true for a face its neighbour hides; null asks about unculled quads and must
     *                 answer false
     */
    void emitBlock(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state,
                   RandomSource random, Predicate<@Nullable Direction> cullTest) {
        prepare(cullTest);
        try {
            model.emitQuads(emitter, level, pos, state, random, cullTest);
        } finally {
            this.cullTest = NO_CULL;
        }
    }

    /**
     * Sets the cull test for quads emitted straight into {@link #emitter()} and clears whatever
     * a previous, abandoned quad left in the emitter.
     */
    void prepare(@Nullable Predicate<@Nullable Direction> cullTest) {
        this.cullTest = cullTest != null ? cullTest : NO_CULL;
        emitter.clear();
    }

    @Override
    public void accept(MutableQuadView quad) {
        Direction cullFace = quad.cullFace();
        if (cullFace != null && cullTest.test(cullFace)) {
            return;
        }
        sink.quad(quad);
    }
}
