/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixRenderStateOwner;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives entity and block-entity render states the game object they were extracted from;
 * see {@link RemixRenderStateOwner}. A plain field rather than Fabric's render-state data
 * map, because every entity and block entity in view is tagged every frame.
 */
@Mixin({EntityRenderState.class, BlockEntityRenderState.class})
public abstract class RenderStateOwnerMixin implements RemixRenderStateOwner {

    @Unique
    private Object rtxremix$owner;

    @Override
    public Object rtxremix$owner() {
        return rtxremix$owner;
    }

    @Override
    public void rtxremix$setOwner(Object owner) {
        rtxremix$owner = owner;
    }
}
