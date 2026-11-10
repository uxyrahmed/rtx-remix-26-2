/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Samples the model of the block currently being meshed, so a block light rule can be
 * positioned against the geometry that actually rendered.
 *
 * <p>Rules see only a {@link BlockState} and a {@link BlockPos}, which is enough for a block
 * whose model is fixed. It is not enough for one whose blockstate picks among rotated variants:
 * the rotation lives in the blockstate JSON, not in a property, so no offset table written
 * against the unrotated model can follow it. Only the mesh emitter knows which variant a
 * position drew, so it hands that knowledge over through this.
 *
 * <p>The model is read through the same {@code emitQuads} path the capture reads it through
 * ({@link RemixRenderContext}), with the same seed and without face culling, so a sampled
 * variant is the drawn variant and every face of it counts.
 *
 * <p>One instance per {@link BlockQuadEmitter}, therefore per worker thread. {@link #begin}
 * resets it for the next block; everything is computed lazily and memoised, so a block whose
 * rules never ask pays only the reset.
 */
public final class ModelSampling implements BlockLightRuleManager.ModelSampler {

    private static final Predicate<@Nullable Direction> NO_CULL = face -> false;

    /** Own random, so sampling cannot perturb the emitter's model randomisation. */
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0L);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    /** Created on the first sample, so a thread whose blocks carry no light rule never makes one. */
    private RemixRenderContext context;

    private BlockStateModelSet blockModels;
    private BlockAndTintGetter level;
    private BlockState state;
    private long seed;

    private float[] vertices = new float[3 * 256];
    private int vertexCount;
    private boolean verticesCollected;

    private final float[] centroid = new float[3];
    private boolean centroidComputed;

    /** Rebinds this sampler to a new block and drops everything memoised. */
    public void begin(BlockStateModelSet blockModels, BlockAndTintGetter level, BlockPos pos,
                      BlockState state, long seed) {
        this.blockModels = blockModels;
        this.level = level;
        this.pos.set(pos);
        this.state = state;
        this.seed = seed;
        this.vertexCount = 0;
        this.verticesCollected = false;
        this.centroidComputed = false;
    }

    /** Drops the references to the last block's region and models once a section is done. */
    public void clear() {
        this.blockModels = null;
        this.level = null;
        this.state = null;
    }

    @Override
    public float[] centroid() {
        if (centroidComputed) return centroid;
        centroidComputed = true;

        collect();
        if (vertexCount == 0) {
            centroid[0] = 0.5f;
            centroid[1] = 0.5f;
            centroid[2] = 0.5f;
            return centroid;
        }

        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < vertexCount * 3; i += 3) {
            sx += vertices[i];
            sy += vertices[i + 1];
            sz += vertices[i + 2];
        }
        centroid[0] = (float) (sx / vertexCount);
        centroid[1] = (float) (sy / vertexCount);
        centroid[2] = (float) (sz / vertexCount);
        return centroid;
    }

    private void collect() {
        if (verticesCollected) return;
        verticesCollected = true;

        if (blockModels == null || state == null || level == null) return;
        BlockStateModel model = blockModels.get(state);
        if (model == null) return;

        if (context == null) {
            context = new RemixRenderContext(this::collectQuad);
        }
        // Seeded exactly as the emission seeds it, so a multi-variant model resolves to the
        // same variant here as on screen.
        random.setSeed(seed);
        context.emitBlock(model, level, pos, state, random, NO_CULL);
    }

    private void collectQuad(QuadView quad) {
        ensureCapacity(vertexCount + 4);
        for (int v = 0; v < 4; v++) {
            int w = vertexCount * 3;
            vertices[w] = quad.x(v);
            vertices[w + 1] = quad.y(v);
            vertices[w + 2] = quad.z(v);
            vertexCount++;
        }
    }

    private void ensureCapacity(int wantedVertices) {
        if (wantedVertices * 3 <= vertices.length) return;
        int cap = vertices.length;
        while (cap < wantedVertices * 3) cap <<= 1;
        float[] grown = new float[cap];
        System.arraycopy(vertices, 0, grown, 0, vertexCount * 3);
        vertices = grown;
    }
}
