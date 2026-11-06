/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>Since 26.1 vanilla's {@link PoseStack} keeps its popped entries and copies them
 * back into service on the next push, which is what this class used to add on 1.21.1,
 * so pushing and popping here allocates nothing. What remains is the model-part
 * transform: vanilla's {@code ModelPart.translateAndRotate} allocates a quaternion
 * for every rotated part, every frame, and {@code ModelPartMixin} routes that
 * through {@link #applyModelPartTransform} on this stack instead. The pooled
 * quaternion belongs to this stack and therefore never changes vanilla rendering
 * outside the capture pass.
 */
public final class RecyclingMatrixStack extends PoseStack {

    private final Quaternionf modelPartRotation = new Quaternionf();

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        while (!isEmpty()) {
            popPose();
        }
        setIdentity();
    }

    /** Applies vanilla's {@code ModelPart.translateAndRotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float pivotX, float pivotY, float pivotZ,
                                        float pitch, float yaw, float roll,
                                        float xScale, float yScale, float zScale) {
        translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        if (pitch != 0.0F || yaw != 0.0F || roll != 0.0F) {
            mulPose(modelPartRotation.rotationZYX(roll, yaw, pitch));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }
}
