/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * The game object a render state was extracted from, added to
 * {@code EntityRenderState} and {@code BlockEntityRenderState} by
 * {@code RenderStateOwnerMixin}.
 *
 * <p>A render state is created afresh every frame and carries only what its renderer
 * reads, while the Remix capture keys retained instances by the identity of the entity
 * or block entity they belong to. {@code EntityRenderDispatcherMixin} and
 * {@code BlockEntityRenderDispatcherMixin} set the owner as vanilla extracts each state,
 * on the render thread, so the capture that later submits the state knows whose parts it
 * records. The reference lives only as long as the state, which vanilla drops once the
 * frame's states are submitted.
 */
public interface RemixRenderStateOwner {

    /** The entity or block entity this state was extracted from, or null if unknown. */
    Object rtxremix$owner();

    void rtxremix$setOwner(Object owner);
}
