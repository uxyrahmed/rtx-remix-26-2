/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pairs a bitmap font sheet's GPU staging buffer with the CPU image it was filled
 * from, so a glyph copied out of the buffer can be mirrored from the image.
 *
 * <p>{@code gpuData} creates the staging buffer once per sheet and returns it to every
 * glyph upload, and the sheet image stays open until the provider closes both.
 */
@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder")
public abstract class BitmapGlyphSourceMixin {

    @Shadow @Final private NativeImage image;

    @Inject(method = "gpuData", at = @At("RETURN"))
    private void rtxremix$registerGlyphSheet(CallbackInfoReturnable<GpuBuffer> cir) {
        GlyphPageMirror.registerBitmapSource(cir.getReturnValue(), image);
    }
}
