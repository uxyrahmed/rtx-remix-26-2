/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.HeldItemFlame;
import com.nvidia.rtxremix.minecraft.entity.RemixItemStackRenderState;
import com.nvidia.rtxremix.minecraft.light.HeldLightState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records on an item's render state what the Remix capture takes from the item, while
 * the item and its holder are still known; see {@link RemixItemStackRenderState}.
 *
 * <p>{@code updateForTopItem} is where every item render state is filled: held items as
 * their holder is extracted, item entities, frames and displays as theirs are, and the
 * first-person hands as they are submitted. Whether a lit element burns is decided here,
 * once, by {@link HeldLightState}, the one place that decision lives: a hand-held
 * element held by a living entity asks it; an element shown in a hand pose by anything
 * else, an item_display for instance, has nothing that can put it out, so it burns.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(
        method = "updateForTopItem(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V",
        at = @At("RETURN")
    )
    private void rtxremix$recordCaptureInfo(ItemStackRenderState output, ItemStack item,
                                            ItemDisplayContext displayContext, @Nullable Level level,
                                            @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        Block block = item.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        SimpleParticleType burningFlame = null;
        if (HeldItemFlame.isHandMode(displayContext)) {
            SimpleParticleType flame = HeldItemFlame.particleFor(item);
            if (flame != null && (!(owner instanceof LivingEntity holder) || HeldLightState.isLit(holder, item))) {
                burningFlame = flame;
            }
        }
        ((RemixItemStackRenderState) output).rtxremix$setCaptureInfo(block, burningFlame);
    }
}
