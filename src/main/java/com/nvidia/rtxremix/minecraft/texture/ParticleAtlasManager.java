/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Captures the atlases particles sample besides the block atlas, the particle
 * atlas and the item atlas, and builds parallel LabPBR atlas textures plus one
 * atlas-wide material for each.
 *
 * <p>The particle atlas material is a {@link MaterialInfoOpaqueEXT} that carries
 * its own alpha test rather than draw-call alpha state. Blending is chosen per
 * instance by {@code RemixParticleRenderer} through {@code InstanceInfoBlendEXT},
 * so one material serves both the alpha-tested and the blended particle passes.
 *
 * <p>The item atlas material is built like the block atlas cutout material of
 * {@link BlockTextureManager}, because item-breaking particles draw like terrain
 * particles: items shared the block atlas until the atlases split, and those
 * particles drew with that material.
 *
 * <p>Block-atlas particles reuse materials from {@link BlockTextureManager}
 * rather than creating new ones here.
 */
public class ParticleAtlasManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleAtlasManager.class);
    private static final float ITEM_ATLAS_DISPLACE_IN = 0.25f;
    private static final float ITEM_ATLAS_EMISSIVE_INTENSITY = 100.0f;

    private final RemixRenderer renderer;

    private final List<RemixTexture> managedTextures = new ArrayList<>();
    private final List<RemixMaterial> managedMaterials = new ArrayList<>();

    private RemixMaterial material;
    private RemixMaterial itemAtlasMaterial;

    private boolean initialized;
    private boolean initFailed;

    public ParticleAtlasManager(RemixRenderer renderer) {
        this.renderer = renderer;
    }

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            RemixAPI api = renderer.getRemixApi();
            Minecraft client = Minecraft.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            TextureAtlas particleAtlas = stitchedAtlas(client, TextureAtlas.LOCATION_PARTICLES);
            if (particleAtlas == null) {
                LOGGER.warn("Particle atlas is not stitched yet, deferring init");
                return;
            }

            ResourceManager rm = client.getResourceManager();
            LabPBRConverter.VariantCache labPBRVariants = LabPBRConverter.buildVariantCache(rm);

            // Particle sprites are named without their texture directory.
            AtlasTextures particleTextures = captureAtlas(particleAtlas, "particle_atlas", labPBRVariants,
                    spriteId -> Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
                            "particle/" + spriteId.getPath()));
            if (particleTextures == null) {
                LOGGER.error("Failed to upload particle atlas albedo, aborting");
                initFailed = true;
                return;
            }
            material = buildParticleMaterial(particleTextures);
            if (material == null) {
                LOGGER.error("Failed to create particle material(s)");
                initFailed = true;
                return;
            }

            itemAtlasMaterial = buildItemAtlasMaterial(client, labPBRVariants);

            initialized = true;
            LOGGER.info("Particle atlas initialized ({}x{}, {} sprites, normal={}, height={}, emissive={}, itemAtlas={})",
                    particleTextures.atlasWidth(), particleTextures.atlasHeight(), particleTextures.spriteCount(),
                    particleTextures.normal() != null, particleTextures.heightMap() != null,
                    particleTextures.emissive() != null, itemAtlasMaterial != null);

        } catch (Exception e) {
            LOGGER.error("Particle atlas init failed", e);
            initFailed = true;
        }
    }

    /**
     * Builds the item atlas material, or returns null and leaves item-atlas particles
     * undrawn when the atlas is not stitched or its textures fail to upload.
     */
    private RemixMaterial buildItemAtlasMaterial(Minecraft client, LabPBRConverter.VariantCache labPBRVariants) {
        TextureAtlas itemAtlas = stitchedAtlas(client, TextureAtlas.LOCATION_ITEMS);
        if (itemAtlas == null) {
            LOGGER.warn("Item atlas is not stitched; item-breaking particles stay undrawn");
            return null;
        }
        // Item sprites are named with their texture directory.
        AtlasTextures itemTextures = captureAtlas(itemAtlas, "item_atlas", labPBRVariants,
                UnaryOperator.identity());
        if (itemTextures == null) {
            LOGGER.error("Failed to upload item atlas albedo; item-breaking particles stay undrawn");
            return null;
        }
        return buildItemMaterial(itemTextures);
    }

    private static TextureAtlas stitchedAtlas(Minecraft client, Identifier location) {
        AbstractTexture tex = client.getTextureManager().getTexture(location);
        if (!(tex instanceof TextureAtlas atlas)) return null;
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        if (acc.getAtlasWidth() == 0 || acc.getAtlasHeight() == 0) return null;
        Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();
        return sprites == null || sprites.isEmpty() ? null : atlas;
    }

    /**
     * Stitches {@code atlas} on the CPU and uploads it with its LabPBR channels.
     *
     * @param textureIdOf maps a sprite id to the texture id its {@code _n} and {@code _s}
     *                    siblings are named after
     * @return the uploaded textures, or null when the albedo failed to upload
     */
    private AtlasTextures captureAtlas(TextureAtlas atlas, String name,
                                       LabPBRConverter.VariantCache labPBRVariants,
                                       UnaryOperator<Identifier> textureIdOf) {
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        int atlasW = acc.getAtlasWidth();
        int atlasH = acc.getAtlasHeight();
        Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();

        byte[] albedoRGBA = BlockTextureManager.stitchAtlasRGBA(atlas);

        int pixels = atlasW * atlasH;
        byte[] normalAtlas    = new byte[pixels * 4];
        byte[] heightAtlas    = new byte[pixels];
        byte[] roughnessAtlas = new byte[pixels];
        byte[] metallicAtlas  = new byte[pixels];
        byte[] emissiveAtlas  = new byte[pixels * 4];

        Arrays.fill(heightAtlas, (byte) 255);
        Arrays.fill(roughnessAtlas, (byte) 180);
        for (int i = 0; i < pixels; i++) {
            int idx4 = i * 4;
            normalAtlas[idx4]     = (byte) 128;
            normalAtlas[idx4 + 1] = (byte) 128;
            normalAtlas[idx4 + 2] = (byte) 255;
            normalAtlas[idx4 + 3] = (byte) 255;
        }

        boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false;

        for (var entry : sprites.entrySet()) {
            TextureAtlasSprite sprite = entry.getValue();
            int pixX = BlockTextureManager.spriteContentX(sprite);
            int pixY = BlockTextureManager.spriteContentY(sprite);
            int pixW = sprite.contents().width();
            int pixH = sprite.contents().height();

            Identifier texId = textureIdOf.apply(entry.getKey());
            NativeImage nImg = labPBRVariants.loadVariant(texId, "_n");
            NativeImage sImg = labPBRVariants.loadVariant(texId, "_s");
            try {
                if (nImg != null) {
                    LabPBRConverter.blitNormal(normalAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.blitHeight(heightAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.applyAO(albedoRGBA, atlasW, nImg, pixX, pixY, pixW, pixH);
                    hasAnyNormal = true;
                    hasAnyHeight = true;
                }
                if (sImg != null) {
                    LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                }
                if (sImg != null && LabPBRConverter.hasEmissiveData(sImg)) {
                    LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sImg,
                            pixX, pixY, pixW, pixH, 0);
                    hasAnyEmissive = true;
                }
            } finally {
                if (nImg != null) nImg.close();
                if (sImg != null) sImg.close();
            }
        }

        RemixTexture albedoTex = uploadTexture(name + "_albedo",
                albedoRGBA, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        if (albedoTex == null) {
            return null;
        }

        RemixTexture normalTex = hasAnyNormal
                ? uploadTexture(name + "_normal", normalAtlas, atlasW, atlasH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX) : null;
        RemixTexture heightTex = hasAnyHeight
                ? uploadTexture(name + "_height", heightAtlas, atlasW, atlasH,
                        TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP) : null;
        RemixTexture roughnessTex = uploadTexture(name + "_roughness",
                roughnessAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture metallicTex = uploadTexture(name + "_metallic",
                metallicAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture emissiveTex = hasAnyEmissive
                ? uploadTexture(name + "_emissive", emissiveAtlas, atlasW, atlasH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;

        return new AtlasTextures(atlasW, atlasH, sprites.size(), albedoTex, normalTex, heightTex,
                roughnessTex, metallicTex, emissiveTex);
    }

    private RemixMaterial buildParticleMaterial(AtlasTextures textures) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(1f)
                .metallicConstant(0f)
                .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                .useDrawCallAlphaState(false)
                .alphaTestType(4)
                .alphaReferenceValue(10);
        if (textures.roughness() != null) ob.roughnessTexture(textures.roughness().getHandle());
        if (textures.metallic() != null)  ob.metallicTexture(textures.metallic().getHandle());
        if (textures.heightMap() != null)    ob.heightTexture(textures.heightMap().getHandle()).displaceIn(0.02f);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(textures.albedo().getHandle())
                .filterMode(0);
        if (textures.normal() != null)   mb.normalTexture(textures.normal().getHandle());
        if (textures.emissive() != null) mb.emissiveTexture(textures.emissive().getHandle()).emissiveIntensity(40.0f);

        return registerMaterial(mb.opaqueExt(ob.build()).build());
    }

    private RemixMaterial buildItemMaterial(AtlasTextures textures) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f).roughnessConstant(1f).metallicConstant(1f)
                .useDrawCallAlphaState(true)
                .alphaTestType(4)
                .alphaReferenceValue(128);
        if (textures.roughness() != null) ob.roughnessTexture(textures.roughness().getHandle());
        if (textures.metallic() != null)  ob.metallicTexture(textures.metallic().getHandle());
        if (textures.heightMap() != null)    ob.heightTexture(textures.heightMap().getHandle()).displaceIn(ITEM_ATLAS_DISPLACE_IN);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(textures.albedo().getHandle())
                .filterMode(0);
        if (textures.normal() != null)   mb.normalTexture(textures.normal().getHandle());
        if (textures.emissive() != null) {
            mb.emissiveTexture(textures.emissive().getHandle())
                    .emissiveIntensity(ITEM_ATLAS_EMISSIVE_INTENSITY);
        }

        return registerMaterial(mb.opaqueExt(ob.build()).build());
    }

    /** Material of the particle atlas. */
    public RemixMaterial getMaterial()  { return material; }

    /** Material of the item atlas, or null when it could not be built. */
    public RemixMaterial getItemAtlasMaterial() { return itemAtlasMaterial; }

    public boolean isInitialized()     { return initialized; }

    private RemixTexture uploadTexture(String name, byte[] data, int w, int h, int fmt, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            RemixTexture tex = new RemixTexture(w, h, fmt, purpose, buf);
            // The texture owns the buffer from here: RemixAssetBase disposes it after a
            // successful create and on the create failure path alike, so releasing it
            // here as well would release it twice.
            RemixTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload particle texture '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private RemixMaterial registerMaterial(MaterialInfo matInfo) {
        try {
            RemixMaterial mat = new RemixMaterial(matInfo);
            RemixMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create particle material: {}", e.getMessage());
            return null;
        }
    }

    public void cleanup() {
        for (RemixMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();

        for (RemixTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();

        material = null;
        itemAtlasMaterial = null;
        initialized = false;
        initFailed = false;
    }

    /** One captured atlas: its size, sprite count and uploaded channel textures. */
    private record AtlasTextures(int atlasWidth, int atlasHeight, int spriteCount,
                                 RemixTexture albedo, RemixTexture normal, RemixTexture heightMap,
                                 RemixTexture roughness, RemixTexture metallic, RemixTexture emissive) {
    }
}
