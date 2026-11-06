/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.MatrixUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The {@link SubmitNodeCollector} every Remix capture pass submits into, and the
 * store of the per-layer consumers it captures with.
 *
 * <h2>Entity capture on 26.x</h2>
 * <p>On 1.21.1 vanilla drew an entity, block entity or the first-person hands straight
 * into a {@code MultiBufferSource}, and a capture replaced that source with this one, so
 * the model was evaluated once and its vertices landed here instead of in OpenGL. Since
 * 1.21.9 rendering is split in three: an {@code EntityRenderState} (or
 * {@code BlockEntityRenderState}, {@code ItemStackRenderState}) is extracted from the
 * game object, the renderer then <em>submits</em> nodes for that state to a
 * {@code SubmitNodeCollector}, and only later does {@code FeatureRenderDispatcher}
 * evaluate every node into vertices, sorted and batched across all entities. The
 * capture keeps the 1.21.1 shape by standing in at the submit step:
 * <ol>
 *   <li>Vanilla's extraction runs untouched. {@code EntityRenderDispatcherMixin} and
 *   {@code BlockEntityRenderDispatcherMixin} tag each extracted state with the game
 *   object it came from ({@link RemixRenderStateOwner}), because retained Remix
 *   instances are keyed by owner identity and a render state is a fresh object every
 *   frame. Item states record their block and the holder's lit-element decision the
 *   same way ({@link RemixItemStackRenderState}), since the holder is only known
 *   during extraction.</li>
 *   <li>Where vanilla submits a state ({@code LevelRenderer.submitEntities},
 *   {@code BlockEntityRenderDispatcher.submit}, {@code ItemInHandRenderer.submitHandsWithItems}),
 *   the capture passes hand the renderer this collector, on their own identity-rooted
 *   matrix stack, instead of vanilla's storage. Vanilla's storage never sees those
 *   submits, so nothing is drawn twice.</li>
 *   <li>This collector evaluates every node the moment it is submitted, exactly as the
 *   matching feature renderer would ({@code ModelFeatureRenderer.prepareModel} is
 *   {@code setupAnim} then {@code renderToBuffer}; {@code ItemFeatureRenderer},
 *   {@code TextFeatureRenderer}, {@code FlameFeatureRenderer} and the rest put their
 *   quads the same way), into the consumer {@link #getBuffer} returns for the node's
 *   render type. A model is therefore still evaluated once per frame, on the render
 *   thread, before the next owner submits, so the pooled consumers recycle and the
 *   thread-local {@link EntityRenderContext} sees each owner's bones in order.</li>
 * </ol>
 * The owner's capture is consumed by {@code RemixEntitySubmitter} straight after its
 * submit returns, as on 1.21.1: rigid occurrences retain instances keyed by owner,
 * category, layer and part, while strips and beams stream transient meshes. The
 * off-frustum sweep extracts states vanilla culled and submits them into this collector
 * the same way. What vanilla still needs from a captured entity is only its outline
 * when it glows: those submits are forwarded to vanilla's own collector, placed where
 * vanilla would have placed them, see {@link #forwardOutlinesTo}. Shadows are dropped,
 * because Remix traces shadows from the scene's lights.
 *
 * <h2>Usage per capture</h2>
 * <ol>
 *   <li>Call {@link #beginEntity()} before submitting</li>
 *   <li>Pass this collector to the vanilla renderer's submit</li>
 *   <li>Call {@link #endEntity()} after submitting; it returns all captured layers</li>
 * </ol>
 */
public class RemixVertexConsumerProvider implements SubmitNodeCollector {

    /**
     * Consumers created during the current entity's capture, keyed by RenderType, in
     * request order. Multiple layers per entity are common (body + eyes + glint) but the
     * count is small, so parallel arrays with a linear scan replace a map: a map entry per
     * layer per entity was a steady allocation on the render thread.
     */
    private RenderType[] activeLayers = new RenderType[8];
    private RemixCapturingConsumer[] activeConsumers = new RemixCapturingConsumer[8];
    private int activeCount;

    /** Pool of consumers for reuse. Only safe when consumers are consumed before next beginEntity(). */
    private final List<RemixCapturingConsumer> consumerPool = new ArrayList<>();

    /** Reusable result buffer, only safe when caller consumes before next beginEntity(). */
    private final List<CapturedLayer> resultBuffer = new ArrayList<>();

    /** The block the captured geometry is local to; see {@link #setBlockOrigin}. */
    private int originX, originY, originZ;

    /** Vanilla's collector, which still draws a captured entity's outline; see {@link #forwardOutlinesTo}. */
    private @Nullable SubmitNodeCollector outlineTarget;
    private float outlineOffsetX, outlineOffsetY, outlineOffsetZ;

    private final QuadInstance quadInstance = new QuadInstance();
    private final GlyphCapture glyphs = new GlyphCapture();
    private final MovingBlockCapture movingBlocks = new MovingBlockCapture();
    private final MeshQuadCapture meshQuads = new MeshQuadCapture();

    // ---- Per-capture lifecycle ----

    /**
     * Begins capturing for a new entity. Clears previous capture state.
     */
    public void beginEntity() {
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.clear();
            consumerPool.add(consumer);
            activeConsumers[i] = null;
            activeLayers[i] = null;
        }
        activeCount = 0;
        originX = 0;
        originY = 0;
        originZ = 0;
        outlineTarget = null;
    }

    /**
     * Sets the block whose local space the current capture is in, after
     * {@link #beginEntity}. Only surfaces whose texture coordinates are projected from
     * position read it, so neighbouring end portal blocks continue one texture; a
     * capture that never sets it projects as if at the origin.
     */
    public void setBlockOrigin(BlockPos pos) {
        originX = pos.getX();
        originY = pos.getY();
        originZ = pos.getZ();
    }

    /**
     * Sends the outline submits of the current capture to vanilla's {@code target}, after
     * {@link #beginEntity}. A glowing entity's outline is drawn by vanilla into the
     * outline buffer its post-processor reads, which nothing in the traced scene feeds,
     * so vanilla still has to draw it. The capture's matrix stack is rooted at the
     * entity, so each forwarded pose is moved by {@code (dx, dy, dz)}, the entity's
     * position relative to the camera that vanilla would have rendered it at.
     * Only the outline is forwarded: the entity itself, its blob shadow included, stays
     * out of the OpenGL frame that is composited over the traced scene.
     */
    public void forwardOutlinesTo(SubmitNodeCollector target, double dx, double dy, double dz) {
        outlineTarget = target;
        outlineOffsetX = (float) dx;
        outlineOffsetY = (float) dy;
        outlineOffsetZ = (float) dz;
    }

    /**
     * Ends capturing for the current entity and returns all captured layers.
     * The returned list is reused; callers must consume it before the next
     * {@link #beginEntity()} call.
     */
    public List<CapturedLayer> endEntity() {
        outlineTarget = null;
        resultBuffer.clear();
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.finishOpenVertex();
            // A cuboid part carries no vertices, so a layer can be non-empty with zero
            // captured vertices. layerIndex is assigned at first request, so dropping
            // an empty layer here cannot shift the others.
            if (consumer.getVertexCount() > 0 || consumer.getPartCount() > 0) {
                CapturedLayer layer = consumer.capturedLayer();
                layer.bind(activeLayers[i]);
                resultBuffer.add(layer);
            }
        }
        // The active set is kept: beginEntity() returns it to the pool.
        return resultBuffer;
    }

    // ---- Layer consumers ----

    /**
     * The consumer that captures geometry drawn for {@code layer}, one per layer per
     * capture, or a consumer that discards everything for a layer Remix cannot draw.
     */
    public VertexConsumer getBuffer(RenderType layer) {
        // Reuse consumer for same layer within one entity (e.g. left arm + right arm)
        for (int i = 0; i < activeCount; i++) {
            if (activeLayers[i].equals(layer)) {
                return activeConsumers[i];
            }
        }

        Optional<Identifier> texOpt = RenderLayerUtils.capturableTexture(layer);
        Identifier textureId;
        if (texOpt.isPresent()) {
            textureId = texOpt.get();
        } else if (RenderLayerUtils.isCapturableUntexturedLayer(layer)) {
            // Shaded entirely by vertex colour, so it needs an identity to hang a
            // material on but no texture of its own. In vanilla the lead is the only
            // layer that reaches this.
            textureId = RenderLayerUtils.UNTEXTURED_LAYER_ID;
        } else {
            // Nothing Remix can draw: shadow, debug overlays, lines, depth-only masks,
            // or text drawn over the whole scene regardless of depth.
            return NoOpVertexConsumer.INSTANCE;
        }

        RenderLayerUtils.Topology topology = RenderLayerUtils.topology(layer)
                .orElse(RenderLayerUtils.Topology.QUADS);
        boolean transientGeometry = RenderLayerUtils.isStreamedPerFrame(layer);
        RemixCapturingConsumer consumer;
        if (!consumerPool.isEmpty()) {
            consumer = consumerPool.remove(consumerPool.size() - 1);
            consumer.reinit(textureId, true, topology, transientGeometry);
        } else {
            consumer = new RemixCapturingConsumer(textureId, true, topology, transientGeometry);
        }
        if (EndPortalSurface.isSurfaceTexture(textureId)) {
            consumer.projectTexcoords(EndPortalSurface.TILE_BLOCKS,
                    EndPortalSurface.SIDE_TILE_HEIGHT_BLOCKS, originX, originY, originZ);
        }
        consumer.setLayerIndex(activeCount);
        if (activeCount == activeLayers.length) {
            activeLayers = Arrays.copyOf(activeLayers, activeCount * 2);
            activeConsumers = Arrays.copyOf(activeConsumers, activeCount * 2);
        }
        activeLayers[activeCount] = layer;
        activeConsumers[activeCount] = consumer;
        activeCount++;
        return consumer;
    }

    private static boolean dropped(VertexConsumer consumer) {
        return consumer == NoOpVertexConsumer.INSTANCE;
    }

    // ---- SubmitNodeCollector: every node is evaluated as it arrives ----

    /** Submission order only sorts vanilla's batches; a capture keeps call order. */
    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    /** Remix traces shadows from the scene's lights, so the blob shadow has no consumer. */
    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    /**
     * Mirrors {@code SubmitNodeCollection.submitNameTag} and {@code NameTagFeatureRenderer}.
     * Only the depth-tested copy of the name is drawn: the see-through copy and both
     * backgrounds land on layers {@link #getBuffer} drops (no depth test, or untextured
     * quads), so they are not laid out at all.
     */
    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset,
                              Component name, boolean seeThrough, int lightCoords,
                              CameraRenderState camera) {
        if (nameTagAttachment == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);
        float x = -font.width(name) / 2.0F;
        int color = seeThrough ? -1 : NAME_TAG_DISCRETE_COLOR;
        int light = seeThrough ? LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2) : lightCoords;
        glyphs.prepare(poseStack.last().pose(), light, Font.DisplayMode.NORMAL);
        font.prepareText(name.getVisualOrderText(), x, offset, color, false, false, 0).visit(glyphs);
        poseStack.popPose();
    }

    /** Vanilla's colour for the name of a sneaking player, half-transparent white. */
    private static final int NAME_TAG_DISCRETE_COLOR = 0x80FFFFFF;

    /** Mirrors {@code TextFeatureRenderer}: plain text, or glowing text's outline under its ink. */
    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string,
                           boolean dropShadow, Font.DisplayMode displayMode, int lightCoords,
                           int color, int backgroundColor, int outlineColor) {
        Font font = Minecraft.getInstance().font;
        Matrix4fc pose = poseStack.last().pose();
        if (outlineColor == 0) {
            glyphs.prepare(pose, lightCoords, displayMode);
            font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(glyphs);
        } else {
            glyphs.prepare(pose, lightCoords, Font.DisplayMode.NORMAL);
            font.prepare8xTextOutline(string, x, y, outlineColor).visit(glyphs);
            glyphs.prepare(pose, lightCoords, Font.DisplayMode.POLYGON_OFFSET);
            font.prepareText(string, x, y, color, false, false, 0).visit(glyphs);
        }
    }

    /**
     * Mirrors {@code FlameFeatureRenderer}: the burning entity's fire sprites on a
     * camera-facing stack of quads, drawn on the block atlas.
     */
    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        VertexConsumer buffer = getBuffer(RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS));
        if (dropped(buffer)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite fire1 = minecraft.getAtlasManager().get(ModelBakery.FIRE_0);
        TextureAtlasSprite fire2 = minecraft.getAtlasManager().get(ModelBakery.FIRE_1);
        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        float s = renderState.boundingBoxWidth * 1.4F;
        pose.scale(s, s, s);
        float r = 0.5F;
        float h = renderState.boundingBoxHeight / s;
        float yo = 0.0F;
        pose.rotate(rotation);
        pose.translate(0.0F, 0.0F, 0.3F - (int) h * 0.02F);
        float zo = 0.0F;
        int ss = 0;
        int lightCoords = LightCoordsUtil.withBlock(renderState.lightCoords, 15);
        while (h > 0.0F) {
            TextureAtlasSprite tex = ss % 2 == 0 ? fire1 : fire2;
            float u0 = tex.getU0();
            float v0 = tex.getV0();
            float u1 = tex.getU1();
            float v1 = tex.getV1();
            if (ss / 2 % 2 == 0) {
                float tmp = u1;
                u1 = u0;
                u0 = tmp;
            }
            fireVertex(pose, buffer, -r, -yo, zo, u1, v1, lightCoords);
            fireVertex(pose, buffer, r, -yo, zo, u0, v1, lightCoords);
            fireVertex(pose, buffer, r, 1.4F - yo, zo, u0, v0, lightCoords);
            fireVertex(pose, buffer, -r, 1.4F - yo, zo, u1, v0, lightCoords);
            h -= 0.45F;
            yo -= 0.45F;
            r *= 0.9F;
            zo -= 0.03F;
            ss++;
        }
        poseStack.popPose();
    }

    private static void fireVertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
                                   float u, float v, int lightCoords) {
        buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setUv1(0, 10).setLight(lightCoords)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static final int LEASH_RENDER_STEPS = 24;
    private static final float LEASH_WIDTH = 0.05F;

    /** Mirrors {@code LeashFeatureRenderer}: one triangle strip down and back up the lead. */
    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        VertexConsumer builder = getBuffer(RenderTypes.leash());
        if (dropped(builder)) {
            return;
        }
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * LEASH_WIDTH / 2.0F;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        poseStack.pushPose();
        poseStack.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);
        Matrix4f pose = poseStack.last().pose();
        for (int k = 0; k <= LEASH_RENDER_STEPS; k++) {
            addLeashVertexPair(builder, pose, dx, dy, dz, LEASH_WIDTH, dxOff, dzOff, k, false, leashState);
        }
        for (int k = LEASH_RENDER_STEPS; k >= 0; k--) {
            addLeashVertexPair(builder, pose, dx, dy, dz, 0.0F, dxOff, dzOff, k, true, leashState);
        }
        poseStack.popPose();
    }

    private static void addLeashVertexPair(VertexConsumer builder, Matrix4fc pose, float dx, float dy, float dz,
                                           float fudge, float dxOff, float dzOff, int k, boolean backwards,
                                           EntityRenderState.LeashState state) {
        float progress = k / (float) LEASH_RENDER_STEPS;
        int block = (int) Mth.lerp(progress, state.startBlockLight, state.endBlockLight);
        int sky = (int) Mth.lerp(progress, state.startSkyLight, state.endSkyLight);
        int lightCoords = LightCoordsUtil.pack(block, sky);
        float colorModifier = k % 2 == (backwards ? 1 : 0) ? 0.7F : 1.0F;
        float r = 0.5F * colorModifier;
        float g = 0.4F * colorModifier;
        float b = 0.3F * colorModifier;
        float x = dx * progress;
        float y;
        if (state.slack) {
            y = dy > 0.0F ? dy * progress * progress : dy - dy * (1.0F - progress) * (1.0F - progress);
        } else {
            y = dy * progress;
        }
        float z = dz * progress;
        builder.addVertex(pose, x - dxOff, y + fudge, z + dzOff).setColor(r, g, b, 1.0F).setLight(lightCoords);
        builder.addVertex(pose, x + dxOff, y + LEASH_WIDTH - fudge, z - dzOff).setColor(r, g, b, 1.0F).setLight(lightCoords);
    }

    /**
     * Mirrors {@code ModelFeatureRenderer.prepareModel}: poses the model from its state
     * and renders it, through the sprite's coordinate wrapper when it has one, which
     * {@code CuboidMixin} looks through so each cube reaches the capture by identity.
     * The crumbling overlay of a block entity being broken is not captured, as it was
     * not on 1.21.1; terrain cracks are {@link RemixBlockDamagePass}'s.
     */
    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable TextureAtlasSprite sprite, int outlineColor,
                                ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
        if (!renderType.isOutline()) {
            VertexConsumer buffer = getBuffer(renderType);
            if (!dropped(buffer)) {
                if (sprite != null) {
                    buffer = sprite.wrap(buffer);
                }
                model.setupAnim(state);
                model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
            }
        }
        if (outlineColor != 0 && outlineTarget != null) {
            RenderType outlineRenderType = outlineRenderType(renderType);
            if (outlineRenderType != null) {
                outlineTarget.submitCustom(SubmitRenderPhases.OUTLINE, new ModelFeatureRenderer.Submit<>(
                        outlineRenderType, forwardedPose(poseStack.last()), model, state,
                        LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, outlineColor, sprite, null));
            }
        }
    }

    private static @Nullable RenderType outlineRenderType(RenderType renderType) {
        return renderType.isOutline() ? renderType : renderType.outline().orElse(null);
    }

    /**
     * Mirrors {@code MovingBlockFeatureRenderer}: a piston's moving block, tessellated with
     * vanilla's block renderer onto the moving-block layers of the block atlas.
     */
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
        movingBlocks.tesselate(poseStack, movingBlockRenderState);
        if (outlineColor != 0 && outlineTarget != null) {
            Matrix4f pose = new Matrix4f(poseStack.last().pose()).translateLocal(
                    outlineOffsetX, outlineOffsetY, outlineOffsetZ);
            outlineTarget.submitCustom(SubmitRenderPhases.OUTLINE,
                    new MovingBlockFeatureRenderer.Submit(pose, movingBlockRenderState, outlineColor));
        }
    }

    /** Mirrors {@code BlockModelFeatureRenderer}: a block model drawn as part of an entity. */
    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (!renderType.isOutline()) {
            VertexConsumer buffer = getBuffer(renderType);
            if (!dropped(buffer)) {
                quadInstance.setLightCoords(lightCoords);
                quadInstance.setOverlayCoords(overlayCoords);
                PoseStack.Pose pose = poseStack.last();
                for (BlockStateModelPart part : parts) {
                    putBlockPartQuads(part, pose, -1, tintLayers, buffer);
                }
            }
        }
        if (outlineColor != 0 && outlineTarget != null) {
            RenderType outlineRenderType = outlineRenderType(renderType);
            if (outlineRenderType != null) {
                outlineTarget.submitCustom(SubmitRenderPhases.OUTLINE, new BlockModelFeatureRenderer.Submit(
                        forwardedPose(poseStack.last()), outlineRenderType, parts,
                        BlockModelRenderState.EMPTY_TINTS, LightCoordsUtil.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, outlineColor, null));
            }
        }
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    private void putBlockPartQuads(BlockStateModelPart part, PoseStack.Pose pose, int baseTint,
                                   int[] tintLayers, VertexConsumer buffer) {
        for (Direction direction : DIRECTIONS) {
            for (BakedQuad quad : part.getQuads(direction)) {
                putBlockQuad(pose, quad, baseTint, tintLayers, buffer);
            }
        }
        for (BakedQuad quad : part.getQuads(null)) {
            putBlockQuad(pose, quad, baseTint, tintLayers, buffer);
        }
    }

    private void putBlockQuad(PoseStack.Pose pose, BakedQuad quad, int baseTint, int[] tintLayers,
                              VertexConsumer buffer) {
        int tintIndex = quad.materialInfo().tintIndex();
        boolean useTintLayer = tintIndex != -1 && tintIndex < tintLayers.length;
        quadInstance.setColor(useTintLayer ? ARGB.multiply(baseTint, tintLayers[tintIndex]) : baseTint);
        buffer.putBakedQuad(pose, quad, quadInstance);
    }

    /**
     * A breaking crack submitted from inside an entity or block entity capture. Vanilla
     * submits terrain cracks outside any capture; {@link RemixBlockDamagePass} captures
     * those where {@code LevelRenderer} submits them.
     */
    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
    }

    /**
     * Line outlines draw on line layers, which Remix cannot draw; the targeted block's
     * outline is {@link RemixBlockOutlinePass}'s.
     */
    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    /**
     * Mirrors {@code ItemFeatureRenderer}: each quad on the item layer its material names,
     * coloured by its tint layer, then the enchantment glint over the same quads.
     *
     * <p>The glint's layer is requested before the item's own. On 1.21.1 the glint and
     * the item shared one vertex stream with the glint's buffer requested first, so the
     * glint took the lower layer index and the item's shell offset sits outside it,
     * which is where the traced item keeps it.
     */
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                           ItemStackRenderState.FoilType foilType) {
        PoseStack.Pose pose = poseStack.last();
        boolean foil = foilType != ItemStackRenderState.FoilType.NONE;
        if (foil && !quads.isEmpty()) {
            getBuffer(foilRenderType(quads.getFirst().materialInfo().itemRenderType()));
        }
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            quadInstance.setColor(layerColor(tintLayers, material));
            getBuffer(material.itemRenderType()).putBakedQuad(pose, quad, quadInstance);
        }
        if (foil) {
            PoseStack.Pose foilDecalPose = foilType == ItemStackRenderState.FoilType.SPECIAL
                    ? foilDecalPose(displayContext, pose)
                    : null;
            for (BakedQuad quad : quads) {
                VertexConsumer foilBuffer = getBuffer(foilRenderType(quad.materialInfo().itemRenderType()));
                if (!dropped(foilBuffer) && foilDecalPose != null) {
                    foilBuffer = new SheetedDecalTextureGenerator(foilBuffer, foilDecalPose, SPECIAL_FOIL_TEXTURE_SCALE);
                }
                foilBuffer.putBakedQuad(pose, quad, quadInstance);
            }
        }
        if (outlineColor != 0 && outlineTarget != null) {
            outlineTarget.submitCustom(SubmitRenderPhases.OUTLINE, new ItemFeatureRenderer.Submit(
                    forwardedPose(pose), displayContext, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    outlineColor, ItemStackRenderState.LayerRenderState.EMPTY_TINTS, quads,
                    ItemStackRenderState.FoilType.NONE));
        }
    }

    private static final float SPECIAL_FOIL_TEXTURE_SCALE = 0.0078125F;

    private static RenderType foilRenderType(RenderType itemRenderType) {
        return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()
                && itemRenderType.outputTarget() == OutputTarget.ITEM_ENTITY_TARGET
                ? RenderTypes.glintTranslucent()
                : RenderTypes.glint();
    }

    private static PoseStack.Pose foilDecalPose(ItemDisplayContext type, PoseStack.Pose pose) {
        PoseStack.Pose foilDecalPose = pose.copy();
        if (type == ItemDisplayContext.GUI) {
            MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.5F);
        } else if (type.firstPerson()) {
            MatrixUtil.mulComponentWise(foilDecalPose.pose(), 0.75F);
        }
        return foilDecalPose;
    }

    private static int layerColor(int[] tintLayers, BakedQuad.MaterialInfo material) {
        if (!material.isTinted()) {
            return -1;
        }
        int layer = material.tintIndex();
        return layer >= 0 && layer < tintLayers.length ? tintLayers[layer] : -1;
    }

    /**
     * An item whose model also emits a Fabric renderer mesh: the vanilla quads as
     * {@link #submitItem(PoseStack, ItemDisplayContext, int, int, int, int[], List, ItemStackRenderState.FoilType)}
     * puts them, then each mesh quad on its own item layer, coloured as Indigo colours
     * it. On 1.21.1 Indigo wrote such quads into the capture's buffer source like any
     * other; the default of this overload would drop them.
     */
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                           MeshView mesh, ItemStackRenderState.FoilType foilType) {
        submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
        if (mesh != null) {
            meshQuads.put(poseStack.last(), tintLayers, -1, overlayCoords, null, mesh);
        }
    }

    /**
     * A block model with a Fabric renderer mesh, drawn as part of an entity: vanilla's
     * parts and the mesh's quads, each on the render type its own layer selects.
     */
    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
                                 boolean translucent, List<BlockStateModelPart> parts, @Nullable Mesh mesh,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                for (BakedQuad quad : part.getQuads(direction)) {
                    putLayeredBlockQuad(pose, quad, renderTypeFunction, tintLayers);
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                putLayeredBlockQuad(pose, quad, renderTypeFunction, tintLayers);
            }
        }
        if (mesh != null) {
            meshQuads.put(pose, tintLayers, -1, overlayCoords, renderTypeFunction, mesh);
        }
    }

    private void putLayeredBlockQuad(PoseStack.Pose pose, BakedQuad quad,
                                     Function<ChunkSectionLayer, RenderType> renderTypeFunction, int[] tintLayers) {
        RenderType renderType = renderTypeFunction.apply(quad.materialInfo().layer());
        if (renderType == null || renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = getBuffer(renderType);
        if (!dropped(buffer)) {
            putBlockQuad(pose, quad, -1, tintLayers, buffer);
        }
    }

    /** A crack inside an entity or block entity capture; see the vanilla overload. */
    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, Mesh mesh, int progress) {
    }

    /**
     * Puts the quads of a Fabric renderer mesh into the capture as baked quads. A mesh
     * quad carries no sprite, which only the capture's per-sprite card rebuild and
     * texture-coordinate bounds read; both fall back to what the quad's own coordinates
     * give, as for any spriteless quad.
     */
    private final class MeshQuadCapture implements Consumer<QuadView> {
        private final QuadInstance instance = new QuadInstance();
        private PoseStack.Pose pose;
        private int[] tintLayers;
        private int baseTint;
        private @Nullable Function<ChunkSectionLayer, RenderType> renderTypeFunction;

        void put(PoseStack.Pose pose, int[] tintLayers, int baseTint, int overlayCoords,
                 @Nullable Function<ChunkSectionLayer, RenderType> renderTypeFunction, MeshView mesh) {
            this.pose = pose;
            this.tintLayers = tintLayers;
            this.baseTint = baseTint;
            this.renderTypeFunction = renderTypeFunction;
            instance.setOverlayCoords(overlayCoords);
            try {
                mesh.forEach(this);
            } finally {
                this.pose = null;
                this.tintLayers = null;
                this.renderTypeFunction = null;
            }
        }

        @Override
        public void accept(QuadView quad) {
            RenderType renderType = renderTypeFunction != null
                    ? renderTypeFunction.apply(quad.chunkLayer())
                    : quad.itemRenderType();
            if (renderType == null || renderType.isOutline()) {
                return;
            }
            VertexConsumer buffer = getBuffer(renderType);
            if (dropped(buffer)) {
                return;
            }
            int tintIndex = quad.tintIndex();
            int tint = tintIndex >= 0 && tintIndex < tintLayers.length
                    ? ARGB.multiply(baseTint, tintLayers[tintIndex])
                    : baseTint;
            for (int vertex = 0; vertex < 4; vertex++) {
                instance.setColor(vertex, ARGB.multiply(quad.color(vertex), tint));
            }
            buffer.putBakedQuad(pose, quad.toBakedQuad(null), instance);
        }
    }

    /** Mirrors {@code CustomFeatureRenderer}: the renderer writes straight into the layer's consumer. */
    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (renderType.isOutline()) {
            if (outlineTarget != null) {
                outlineTarget.submitCustom(SubmitRenderPhases.OUTLINE, new CustomFeatureRenderer.Submit(
                        forwardedPose(poseStack.last()), renderType, customGeometryRenderer));
            }
            return;
        }
        VertexConsumer buffer = getBuffer(renderType);
        if (!dropped(buffer)) {
            customGeometryRenderer.render(poseStack.last(), buffer);
        }
    }

    /** Particles reach Remix through the particle pipeline, never through an entity capture. */
    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    /** Debug gizmos are screen furniture, not scene geometry. */
    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    /**
     * A modded node with its own feature renderer. Its vertices exist only inside that
     * renderer, which the capture cannot run, and vanilla cannot draw it either because
     * the capture's matrix stack is rooted at the owner rather than the camera, so it is
     * not drawn. On 1.21.1 such geometry went through the buffer source and was captured.
     */
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
        // PORT-STUB(26.2): Fabric custom submit nodes from modded renderers are neither
        // captured nor drawn. 1.21.1 had no such nodes: modded entity geometry reached the
        // capture through MultiBufferSource.getBuffer like vanilla's.
    }

    private PoseStack.Pose forwardedPose(PoseStack.Pose local) {
        PoseStack.Pose pose = local.copy();
        pose.pose().translateLocal(outlineOffsetX, outlineOffsetY, outlineOffsetZ);
        return pose;
    }

    // ---- Text ----

    /** Writes each glyph and text effect into the capture layer of its render type. */
    private final class GlyphCapture implements Font.GlyphVisitor {
        private Matrix4fc pose;
        private int lightCoords;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        void prepare(Matrix4fc pose, int lightCoords, Font.DisplayMode displayMode) {
            this.pose = pose;
            this.lightCoords = lightCoords;
            this.displayMode = displayMode;
        }

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            VertexConsumer buffer = getBuffer(renderable.renderType(displayMode));
            if (!dropped(buffer)) {
                renderable.render(pose, buffer, lightCoords, false);
            }
        }
    }

    // ---- Moving blocks ----

    /** Vanilla's block tessellation, routed onto the moving-block capture layers. */
    private final class MovingBlockCapture implements BlockQuadOutput {
        private @Nullable ModelBlockRenderer blockRenderer;
        private boolean blockRendererAmbientOcclusion;
        private PoseStack poseStack;
        private boolean forceSolid;

        void tesselate(PoseStack poseStack, MovingBlockRenderState state) {
            Minecraft minecraft = Minecraft.getInstance();
            OptionsRenderState options = minecraft.gameRenderer.gameRenderState().optionsRenderState;
            if (blockRenderer == null || blockRendererAmbientOcclusion != options.ambientOcclusion) {
                blockRenderer = new ModelBlockRenderer(options.ambientOcclusion, false, minecraft.getBlockColors());
                blockRendererAmbientOcclusion = options.ambientOcclusion;
            }
            BlockState blockState = state.blockState;
            BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(blockState);
            this.poseStack = poseStack;
            this.forceSolid = ModelBlockRenderer.forceOpaque(options.cutoutLeaves, blockState);
            try {
                blockRenderer.tesselateBlock(this, 0.0F, 0.0F, 0.0F, state, state.blockPos, blockState, model,
                        blockState.getSeed(state.randomSeedPos));
            } finally {
                this.poseStack = null;
            }
        }

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            ChunkSectionLayer layer = forceSolid ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
            RenderType renderType = switch (layer) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            VertexConsumer buffer = getBuffer(renderType);
            if (dropped(buffer)) {
                return;
            }
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            buffer.putBakedQuad(poseStack.last(), quad, instance);
            poseStack.popPose();
        }
    }

    // ---- Result container ----

    /**
     * Captured vertex data for a single RenderType within an entity capture: a view of the
     * consumer that captured it, holding its flat arrays directly (no copy). One instance
     * lives with each pooled consumer and is re-pointed per entity, so a frame of entity
     * capture allocates no layer objects. Valid until the provider's next
     * {@link #beginEntity()}, like the list that holds it.
     */
    public static final class CapturedLayer {
        private final RemixCapturingConsumer consumer;
        private RenderType renderLayer;

        CapturedLayer(RemixCapturingConsumer consumer) {
            this.consumer = consumer;
        }

        void bind(RenderType renderLayer) {
            this.renderLayer = renderLayer;
        }

        public Identifier textureId() { return consumer.getTextureId(); }
        public RenderType renderLayer() { return renderLayer; }
        public RemixCapturingConsumer consumer() { return consumer; }
        public int vertexCount() { return consumer.getVertexCount(); }
        /** Order in which the entity first requested this layer; scales the shell offset. */
        public int layerIndex() { return consumer.getLayerIndex(); }
        /** How to read the vertices as triangles. */
        public RenderLayerUtils.Topology topology() { return consumer.getTopology(); }
        /** Whether the layer's vertex content differs every frame, so a mesh built from it must not be pooled. */
        public boolean transientGeometry() { return consumer.isTransientGeometry(); }
    }

    // ---- No-op consumer for layers Remix cannot draw ----

    private static final class NoOpVertexConsumer implements VertexConsumer {
        static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
        @Override public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) { }
    }
}
