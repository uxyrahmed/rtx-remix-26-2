/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.block.Block;

/**
 * What the Remix capture needs to know about the item an {@code ItemStackRenderState}
 * draws, added to it by {@code ItemStackRenderStateMixin} and filled in by
 * {@code ItemModelResolverMixin} when vanilla resolves the item into the state.
 *
 * <p>On 1.21.1 the item renderer had the {@code ItemStack} and its holder in scope while
 * the geometry reached the capture. Since 26.1 an item is resolved into a render state
 * during extraction and submitted later, from the state alone, so the two facts the
 * capture takes from the stack are recorded on the state while the stack and holder are
 * still known: the block of a block item, whose terrain layer decides how a held pane of
 * glass is shaded, and the flame of a lit element that is burning in its holder's hand,
 * as {@code HeldLightState} decides it.
 */
public interface RemixItemStackRenderState {

    /** Records the resolved item's block, or null, and its burning flame, or null. */
    void rtxremix$setCaptureInfo(Block block, SimpleParticleType burningFlame);

    /** The block of the block item this state draws, or null for any other item. */
    Block rtxremix$block();

    /**
     * The flame particle of the lit element this state's item carries, when that element
     * is burning in its holder's hand, or null.
     */
    SimpleParticleType rtxremix$burningFlame();
}
