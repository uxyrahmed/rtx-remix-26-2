/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderType}.
 *
 * <p>Every vertex a renderer produces is written for a render type, so the type is
 * where geometry and material meet and the one place material identity is read. Since
 * 26.1 a render type is a {@link RenderSetup} (its texture bindings, reached through the
 * class tweaker) over a {@link RenderPipeline} (its program, blend, depth test, vertex
 * format and primitive topology). Together they give the texture the layer is submitted
 * under, if any, the {@link SurfaceMode} Remix shades with, and whether the geometry is
 * streamed afresh every frame; the topology gives the {@link Topology} and whether an
 * untextured layer carries geometry worth submitting at all.
 *
 * <p>Programs are recognised by pipeline identity against {@link RenderPipelines}, the
 * 26.2 counterpart of the 1.21.1 shader shards: a mod-created render type built on a
 * vanilla pipeline classifies exactly as the vanilla type does.
 */
final class RenderLayerUtils {

    /** Material behavior encoded by Minecraft's actual render pipelines, and clear glass. */
    enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        EMISSIVE,
        /**
         * Clear glass on the block atlas: blended, through the refracting material
         * terrain gives placed clear glass. No pipeline encodes it, since vanilla
         * draws clear glass as a cutout; {@code RemixEntitySubmitter.atlasSurfaceMode}
         * assigns it to geometry from a clear-glass block item.
         */
        GLASS
    }

    /** How a layer's captured vertex stream turns into Remix triangles. */
    enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    /**
     * Material identity for layers that draw without a texture. They have no
     * {@link Identifier} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final Identifier UNTEXTURED_LAYER_ID =
            Identifier.fromNamespaceAndPath("rtxremix", "untextured_entity_layer");

    /** The sampler every vanilla render type binds its main texture to. */
    private static final String MAIN_SAMPLER = "Sampler0";

    private RenderLayerUtils() {}

    /**
     * Cache of RenderType -> capturable texture. Most render types are memoized by
     * vanilla, but weak keys avoid retaining dynamic types created by mods or skin features.
     */
    private static final Map<RenderType, Optional<Identifier>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderType -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers two cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it.
     *
     * @param layer the render type (typically from an entity renderer)
     * @return the texture identifier to capture under, or empty to skip the layer
     */
    static Optional<Identifier> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<Identifier> capturableTextureUncached(RenderType layer) {
        if (!depthTested(layer.pipeline())) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    /**
     * Whether a pipeline tests depth at all. The see-through text pipelines carry no
     * depth state; an explicit always-pass test means the same.
     */
    static boolean depthTested(RenderPipeline pipeline) {
        DepthStencilState depth = pipeline.getDepthStencilState();
        return depth != null && depth.depthTest() != CompareOp.ALWAYS_PASS;
    }

    private static Optional<Identifier> extractTexture(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        // The end portal and gateway programs combine two textures in screen space
        // and their vertices carry position only; they draw with a baked texture.
        if (pipeline == RenderPipelines.END_PORTAL) {
            return EndPortalSurface.PORTAL_TEXTURE;
        }
        if (pipeline == RenderPipelines.END_GATEWAY) {
            return EndPortalSurface.GATEWAY_TEXTURE;
        }
        Map<String, RenderSetup.TextureBinding> textures = layer.state.textures;
        if (textures.isEmpty()) {
            return Optional.empty();
        }
        // The main texture is bound to Sampler0; a type with only other bindings (a
        // dissolve mask without a colour texture does not exist in vanilla) falls back
        // to the first, as vanilla's own outline type derivation does.
        RenderSetup.TextureBinding binding = textures.get(MAIN_SAMPLER);
        if (binding == null) {
            binding = textures.values().iterator().next();
        }
        return Optional.of(binding.location());
    }

    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderType layer) {
        return topology(layer.primitiveTopology());
    }

    static Optional<Topology> topology(PrimitiveTopology primitives) {
        return switch (primitives) {
            case QUADS -> Optional.of(Topology.QUADS);
            case TRIANGLE_STRIP -> Optional.of(Topology.TRIANGLE_STRIP);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a layer that draws without a texture still carries geometry worth
     * submitting to Remix. The lead between a mob and its holder is the only such
     * layer in vanilla.
     *
     * <p>Two conditions, both about what the layer can actually shade with.
     * Vertex colour is the only surface detail such a layer has, so a layer
     * without it carries nothing to submit; that excludes the water mask, which
     * exists to write depth. And of the untextured layers that do have colour,
     * the triangle strips are world geometry while the quads are screen
     * furniture such as the nameplate background, which should not become
     * scene geometry that lights and casts shadows.
     */
    static boolean isCapturableUntexturedLayer(RenderType layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        VertexFormat format = layer.format();
        return extractTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                && format != null
                && format.contains(DefaultVertexFormat.COLOR_SEMANTIC_NAME);
    }

    /**
     * Whether the layer's vertex content differs every frame, so no mesh built from
     * it can be shared across frames and none should be pooled.
     *
     * <p>Vanilla streams two kinds of geometry into an entity capture whose vertices
     * never repeat: triangle strips, which it computes from live positions (the lead
     * between a mob and its holder), and the beacon beam, whose texture coordinate
     * scrolls with world time. Everything else it draws is rigid: a model part or baked
     * quad whose pose changes while its local vertices do not.
     */
    static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        boolean beaconBeamProgram = pipeline == RenderPipelines.BEACON_BEAM_OPAQUE
                || pipeline == RenderPipelines.BEACON_BEAM_TRANSLUCENT;
        return classifyStreamedPerFrame(topology(layer).orElse(Topology.QUADS), beaconBeamProgram);
    }

    static boolean classifyStreamedPerFrame(Topology topology, boolean beaconBeamProgram) {
        return topology == Topology.TRIANGLE_STRIP || beaconBeamProgram;
    }

    /**
     * Converts Minecraft's pipeline state into the material behavior that Remix
     * needs. This deliberately inspects pipelines and blend state rather than
     * render-type names so mod-created types built on the standard pipelines
     * behave correctly.
     *
     * <p>The block-breaking crack multiplies the surface under it, a blend Remix
     * has no material for. Its texels are either fully opaque grey or fully
     * transparent, so alpha blending them over the surface is the closest surface
     * behaviour and it is classified as translucent.
     */
    static SurfaceMode surfaceMode(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        // The baked end portal tile is opaque everywhere, so an alpha test would
        // only cost a texture read per hit.
        if (pipeline == RenderPipelines.END_PORTAL || pipeline == RenderPipelines.END_GATEWAY) {
            return SurfaceMode.OPAQUE;
        }
        BlendFunction blend = blendFunction(pipeline);
        return classifySurfaceMode(
                pipeline == RenderPipelines.EYES
                        || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
                BlendFunction.ADDITIVE.equals(blend),
                BlendFunction.TRANSLUCENT.equals(blend)
                        || pipeline == RenderPipelines.CRUMBLING,
                pipeline == RenderPipelines.ENTITY_SOLID
                        || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
                pipeline == RenderPipelines.TEXT
                        || pipeline == RenderPipelines.TEXT_POLYGON_OFFSET
                        || pipeline == RenderPipelines.TEXT_GRAYSCALE
                        || pipeline == RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET);
    }

    private static BlendFunction blendFunction(RenderPipeline pipeline) {
        ColorTargetState target = pipeline.getColorTargetState();
        return target == null ? null : target.blendFunction().orElse(null);
    }

    /**
     * The text programs blend in vanilla only so glyph edges antialias; what they draw,
     * glyphs, map pixels and map icons, is coverage whose texels are either there or
     * not. Cutout keeps those quads out of the blended path, so text and maps are opaque
     * hits that cast shadows and appear in reflections instead of ghosted decals.
     */
    static SurfaceMode classifySurfaceMode(boolean emissiveProgram, boolean additiveTransparency,
                                           boolean translucentTransparency, boolean solidProgram,
                                           boolean textProgram) {
        if (emissiveProgram || additiveTransparency) {
            return SurfaceMode.EMISSIVE;
        }
        if (textProgram) {
            return SurfaceMode.CUTOUT;
        }
        if (translucentTransparency) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (solidProgram) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
