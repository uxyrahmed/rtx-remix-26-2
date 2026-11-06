/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Forwards the atlas's sprite animation to Remix and reports finished stitches.
 *
 * <p>Vanilla animates sprites on the GPU: each atlas tick advances every sprite's
 * animation state and draws the frame on show into the atlas, so no frame pixels pass
 * through the CPU. The forwarder instead reads each advanced state and builds the same
 * frame from the sprite's sheet image. A state does not know its sprite, so the pair is
 * recorded where the atlas creates the state from the sprite, in stitch order.
 */
@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private Identifier location;

    private final List<TextureAtlasSprite> rtxremix$animatedSprites = new ArrayList<>();
    private final List<SpriteContents.AnimationState> rtxremix$animationStates = new ArrayList<>();

    /** Every path that closes the animation states (a restitch, the atlas closing) clears them first. */
    @Inject(method = "clearTextureData", at = @At("HEAD"))
    private void rtxremix$forgetAnimationStates(CallbackInfo ci) {
        rtxremix$animatedSprites.clear();
        rtxremix$animationStates.clear();
    }

    @WrapOperation(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;createAnimationState(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)Lnet/minecraft/client/renderer/texture/SpriteContents$AnimationState;"))
    private SpriteContents.AnimationState rtxremix$pairAnimationState(
            TextureAtlasSprite sprite,
            GpuBufferSlice uboSlice,
            int spriteUboSize,
            Operation<SpriteContents.AnimationState> original) {
        SpriteContents.AnimationState state = original.call(sprite, uboSlice, spriteUboSize);
        if (state != null) {
            rtxremix$animatedSprites.add(sprite);
            rtxremix$animationStates.add(state);
        }
        return state;
    }

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void rtxremix$onAnimatedSpriteTickStart(CallbackInfo ci) {
        AtlasAnimationForwarder.beginAtlasUpload(location);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        try {
            if (AtlasAnimationForwarder.isForwarding(location)) {
                for (int i = 0; i < rtxremix$animationStates.size(); i++) {
                    AtlasAnimationForwarder.forwardAnimationTick(
                            rtxremix$animatedSprites.get(i), rtxremix$animationStates.get(i));
                }
            }
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }
}
