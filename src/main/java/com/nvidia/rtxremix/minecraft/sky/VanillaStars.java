/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The star positions vanilla's sky renderer built, so Remix draws exactly the
 * stars vanilla does.
 *
 * <p>Vanilla builds its star mesh once per sky renderer, uploads it and keeps
 * only the GPU buffer; {@code VanillaSkyRendererMixin} copies the positions out
 * of the mesh on the way. The sky renderer is created with the first sky pass
 * and recreated on resource reloads, from the same seed each time.
 */
public final class VanillaStars {

    private static volatile float[] positions;

    private VanillaStars() {}

    /** Records the vertex positions of the star mesh vanilla is about to upload. */
    public static void capture(MeshData mesh) {
        MeshData.DrawState drawState = mesh.drawState();
        VertexFormat format = drawState.format();
        VertexFormatElement position = format.getElement(DefaultVertexFormat.POSITION_SEMANTIC_NAME);
        if (position == null) {
            positions = new float[0];
            return;
        }

        int vertexCount = drawState.vertexCount();
        int stride = format.getVertexSize();
        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        float[] captured = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * stride + position.offset();
            int destination = vertex * 3;
            captured[destination] = vertices.getFloat(source);
            captured[destination + 1] = vertices.getFloat(source + Float.BYTES);
            captured[destination + 2] = vertices.getFloat(source + 2 * Float.BYTES);
        }
        positions = captured;
    }

    /**
     * A copy of the captured positions, three floats per vertex in quad order, or
     * null while vanilla has not built its stars yet.
     */
    static float[] copyPositions() {
        float[] captured = positions;
        return captured == null ? null : captured.clone();
    }
}
