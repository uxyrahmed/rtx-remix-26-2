/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Copies a glyph upload into its page mirror at the two command encoder primitives
 * glyphs reach the GPU through.
 *
 * <p>TrueType, Unihex and built-in glyphs write a small CPU buffer with
 * {@code writeToTexture}, to which the {@code NativeImage} overloads also delegate,
 * with rows packed at the glyph's width. Bitmap glyphs copy a window of the font
 * sheet from a GPU staging buffer with {@code copyBufferToTexture}; the sheet's CPU
 * image, which {@code BitmapGlyphSourceMixin} registers against that buffer, holds
 * the same bytes. Only an upload inside a glyph upload scope is mirrored, so every
 * other texture write in the game costs one static read.
 */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderUploadMixin {

    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorGlyphWrite(GpuTexture destination, ByteBuffer source,
                                           int mipLevel, int depthOrLayer,
                                           int destX, int destY, int width, int height,
                                           CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = destination.getFormat().blockSize();
        page.blit(destX, destY, width, height, channels, source, width * channels);
    }

    @Inject(method = "copyBufferToTexture", at = @At("HEAD"))
    private void rtxremix$mirrorGlyphCopy(GpuBufferSlice source,
                                          int sourceX, int sourceY,
                                          int sourceWidth, int sourceHeight,
                                          GpuTexture destination,
                                          int destinationX, int destinationY,
                                          int copyWidth, int copyHeight,
                                          int mipLevel, int arrayLayer,
                                          CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        NativeImage sheet = GlyphPageMirror.bitmapSource(source.buffer());
        if (sheet == null) {
            return;
        }
        int channels = sheet.format().components();
        int rowPitch = sourceWidth * channels;
        long first = sheet.getPointer() + source.offset()
                + ((long) sourceY * sourceWidth + sourceX) * channels;
        // The window's rows stride the whole sheet; the buffer spans only the bytes the
        // window touches, from its first pixel to the end of its last row.
        int span = (copyHeight - 1) * rowPitch + copyWidth * channels;
        page.blit(destinationX, destinationY, copyWidth, copyHeight, channels,
                MemoryUtil.memByteBuffer(first, span), rowPitch);
    }
}
