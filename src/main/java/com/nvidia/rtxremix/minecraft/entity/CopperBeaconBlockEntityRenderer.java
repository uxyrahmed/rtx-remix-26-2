/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Renders the independently rotating yoke and lamp head above the static mount.
 *
 * <p>The beacon's articulation is sampled while its render state is extracted, into the
 * same {@link CopperBeaconPose} the per-frame disk light evaluates, so the housing and its
 * light share one pose. The yoke and lamp head are submitted as custom geometry so their
 * copper texture can keep its edge-to-edge strip (see {@code VerticalUvStripConsumer});
 * the lens is a model whose animation comes from the state. Both pose their shared model
 * parts when their node is evaluated, which vanilla does for every beacon in turn long
 * after the last one was submitted, and the Remix capture does as each is submitted.
 * When the Remix capture submits the beacon, its parts become the block entity's retained
 * parts like any other block entity's.
 */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity, CopperBeaconBlockEntityRenderer.CopperBeaconRenderState> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final SpriteId COPPER_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            Identifier.withDefaultNamespace("block/copper_block"));
    static final Identifier LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final Identifier UNLIT_LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final SpriteId LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            LENS_SPRITE_ID);
    private static final SpriteId UNLIT_LENS_TEXTURE = new SpriteId(
            TextureAtlas.LOCATION_BLOCKS,
            UNLIT_LENS_SPRITE_ID);
    private static final RenderType COPPER_RENDER_TYPE = COPPER_TEXTURE.renderType(RenderTypes::entitySolid);

    private final ModelPart yoke;
    private final ModelPart head;
    private final ModelPart lens;
    private final LensModel lensModel;
    private final SpriteGetter sprites;
    /** Roots the yoke traversal at the pose a custom-geometry node is evaluated with. */
    private final PoseStack yokeStack = new PoseStack();
    private final VerticalUvStripConsumer copperUvConsumer = new VerticalUvStripConsumer();

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart bodyRoot = context.bakeLayer(BODY_MODEL_LAYER);
        yoke = bodyRoot.getChild("yoke");
        head = yoke.getChild("head");
        lensModel = new LensModel(context.bakeLayer(LENS_MODEL_LAYER));
        lens = lensModel.lens;
        sprites = context.sprites();
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.ZERO);
        return LayerDefinition.create(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.create(modelData, 6, 6);
    }

    @Override
    public CopperBeaconRenderState createRenderState() {
        return new CopperBeaconRenderState();
    }

    @Override
    public void extractRenderState(CopperBeaconBlockEntity beacon, CopperBeaconRenderState state,
                                   float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beacon, state, partialTicks, cameraPosition, breakProgress);
        BlockState blockState = beacon.getBlockState();
        Direction up = blockState.getValue(CopperBeaconBlock.MOUNT);
        Direction forward = blockState.getValue(BlockStateProperties.FACING);
        double animationTick = CopperBeaconAnimationClock.sample(beacon.getLevel(), partialTicks);
        CopperBeaconPose.evaluate(
                up,
                forward,
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                state.pose);
        state.lensTexture = lensTexture(beacon.isActive(), beacon.getIntensityMultiplier());
        state.lensTint = beacon.getLensTint();
    }

    @Override
    public void submit(CopperBeaconRenderState state, PoseStack matrices,
                       SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(state.pose.mountingRotation());
        matrices.translate(-0.5, -0.5, -0.5);

        TextureAtlasSprite copper = sprites.get(COPPER_TEXTURE);
        int light = state.lightCoords;
        submitNodeCollector.submitCustomGeometry(matrices, COPPER_RENDER_TYPE, (pose, buffer) -> {
            applyArticulation(yoke, head, lens, state.pose);
            yokeStack.last().set(pose);
            yoke.render(yokeStack, copperUvConsumer.wrap(copper.wrap(buffer)), light, OverlayTexture.NO_OVERLAY);
        });
        submitNodeCollector.submitModel(lensModel, state, matrices, light, OverlayTexture.NO_OVERLAY,
                state.lensTint, state.lensTexture, sprites, 0, null);
        matrices.popPose();
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    static void applyArticulation(
            ModelPart yoke,
            ModelPart head,
            ModelPart lens,
            CopperBeaconPose.Pose pose) {
        yoke.yRot = pose.modelYawRadians();
        yoke.xRot = 0.0f;
        head.yRot = 0.0f;
        head.xRot = pose.modelPitchRadians();
        lens.yRot = pose.modelYawRadians();
        lens.xRot = pose.modelPitchRadians();
    }

    static SpriteId lensTexture(boolean active, float intensityMultiplier) {
        return active && intensityMultiplier > 0.0f
                ? LENS_TEXTURE
                : UNLIT_LENS_TEXTURE;
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    /** What a beacon's submit needs, sampled when its state is extracted. */
    public static final class CopperBeaconRenderState extends BlockEntityRenderState {
        final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        SpriteId lensTexture = UNLIT_LENS_TEXTURE;
        int lensTint = -1;
    }

    /** The lens, posed from the beacon's state each time vanilla or the capture evaluates it. */
    private static final class LensModel extends Model<CopperBeaconRenderState> {
        private final ModelPart lens;

        LensModel(ModelPart root) {
            super(root, RenderTypes::entitySolid);
            lens = root.getChild("lens");
        }

        @Override
        public void setupAnim(CopperBeaconRenderState state) {
            super.setupAnim(state);
            lens.yRot = state.pose.modelYawRadians();
            lens.xRot = state.pose.modelPitchRadians();
        }
    }

    /**
     * Keeps resource-pack details along the texture edges while excluding all
     * four corners, where stylized copper textures commonly place rivets.
     */
    private static final class VerticalUvStripConsumer implements VertexConsumer {
        private VertexConsumer delegate;

        VertexConsumer wrap(VertexConsumer delegate) {
            this.delegate = delegate;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            delegate.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, copperTextureV(v));
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
