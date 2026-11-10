/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.block.SpotLightBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Built-in block light rules. Resource packs can override these by using the same rule path.
 *
 * <p>Each rule's light is an {@link Emitter}: its linear RGB colour and how strongly it feeds
 * the volumetric froxel grid. Colours are saturated on purpose, the way coloured-light shader
 * packs read: a light keeps the hue of what burns or glows, rather than the paler average of
 * its texture. The froxel grid is lit by analytic lights, not by emissive surfaces, so open
 * flames feed it at one and a half times their physical radiance and campfires at twice,
 * enough for a soft halo in cave fog. Flames at twice or more wash a torch-lit cave out, and
 * the glows of redstone, conduits and crystals stay at or below the physical value so a room
 * of small emitters does not fog itself.
 *
 * <p>A block with a light here glows only dimly through its own texture
 * ({@code BuiltinEmission}), so its flame or glass keeps its detail, and the light does the
 * lighting. That only works while the light leaves the block's own surface unlit: a light
 * wrapped around an opaque block lights the block's faces far more than anything it reaches,
 * and the block clips to white. Flames are not opaque, so their light sits in the flame; a
 * lantern's light leaves through four one-sided rect lights on its glass, facing outward, and
 * a nether portal's through one on each side of the pane. Glowing cubes such as glowstone
 * and froglights have no light here, because only six such faces per block would avoid
 * lighting the block itself, and a map holds them by the hundred.
 */
final class BlockLightDefaults {
    static final float DEFAULT_AUTHORED_INTENSITY_MULTIPLIER = 5.0f;
    static final float TORCH_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float THIN_SPHERE_RADIUS_SCALE = 0.4f;
    static final float THIN_SPHERE_INTENSITY_SCALE = 3.0f;
    /** Pre-scale emitter radius of a torch's flame, shared with the light reach calibration. */
    static final float TORCH_RADIUS = 0.2f;
    private static final float AMETHYST_INTENSITY_SCALE = 2.0f;
    /** The lantern body is 6/16 wide; its glass, inside a one-pixel frame, is 4/16 by 5/16. */
    private static final float LANTERN_HALF_WIDTH = 3.0f / 16.0f;
    private static final float LANTERN_GLASS_WIDTH = 4.0f / 16.0f;
    private static final float LANTERN_GLASS_HEIGHT = 5.0f / 16.0f;
    private static final float STANDING_LANTERN_BODY_CENTER_Y = 3.5f / 16.0f;
    private static final float HANGING_LANTERN_BODY_CENTER_Y = 4.5f / 16.0f;
    /** The nether portal pane is 4/16 thick. */
    private static final float PORTAL_HALF_THICKNESS = 2.0f / 16.0f;
    /** One-sided lights sit this far in front of the surface they leave through. */
    private static final float SURFACE_LIGHT_OFFSET = 0.005f;
    /** A torch flame's orange. Shared with the light reach calibration. */
    static final LightEntry.ColorOverride TORCH_COLOR = LightEntry.ColorOverride.of(1.0f, 0.35f, 0.1f);
    /** A bigger, cooler-burning wood fire than a torch: deeper orange, still not red. */
    private static final LightEntry.ColorOverride CAMPFIRE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.3f, 0.06f);
    /** Azure: the cyan of soul flames pushed toward blue, so soul light reads as blue. */
    private static final LightEntry.ColorOverride SOUL_COLOR = LightEntry.ColorOverride.of(0.15f, 0.55f, 1.0f);
    /** The green of copper flames. */
    private static final LightEntry.ColorOverride COPPER_COLOR = LightEntry.ColorOverride.of(0.35f, 1.0f, 0.32f);
    /** Deep red; any more green turns orange once encoded for display. */
    private static final LightEntry.ColorOverride REDSTONE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.06f, 0.02f);
    private static final LightEntry.ColorOverride CONDUIT_COLOR = LightEntry.ColorOverride.of(0.45f, 0.9f, 1.0f);
    private static final LightEntry.ColorOverride AMETHYST_COLOR = LightEntry.ColorOverride.of(0.6f, 0.28f, 1.0f);
    private static final LightEntry.ColorOverride WHITE = LightEntry.ColorOverride.of(1.0f, 1.0f, 1.0f);

    private static final float FLAME_VOLUMETRIC_SCALE = 1.5f;
    private static final float CAMPFIRE_VOLUMETRIC_SCALE = 2.0f;
    private static final float CANDLE_VOLUMETRIC_SCALE = 1.0f;
    private static final float GLOW_VOLUMETRIC_SCALE = 0.75f;
    private static final float CRYSTAL_VOLUMETRIC_SCALE = 0.35f;

    private static final Emitter TORCH_FLAME = new Emitter(TORCH_COLOR, FLAME_VOLUMETRIC_SCALE);
    private static final Emitter SOUL_FLAME = new Emitter(SOUL_COLOR, FLAME_VOLUMETRIC_SCALE);
    private static final Emitter COPPER_FLAME = new Emitter(COPPER_COLOR, FLAME_VOLUMETRIC_SCALE);
    private static final Emitter CAMPFIRE_FLAME = new Emitter(CAMPFIRE_COLOR, CAMPFIRE_VOLUMETRIC_SCALE);
    private static final Emitter SOUL_CAMPFIRE_FLAME = new Emitter(SOUL_COLOR, CAMPFIRE_VOLUMETRIC_SCALE);
    private static final Emitter CANDLE_FLAME = new Emitter(TORCH_COLOR, CANDLE_VOLUMETRIC_SCALE);
    private static final Emitter REDSTONE_GLOW = new Emitter(REDSTONE_COLOR, GLOW_VOLUMETRIC_SCALE);
    private static final Emitter CONDUIT_GLOW = new Emitter(CONDUIT_COLOR, GLOW_VOLUMETRIC_SCALE);
    private static final Emitter AMETHYST_GLOW = new Emitter(AMETHYST_COLOR, CRYSTAL_VOLUMETRIC_SCALE);
    private static final Emitter LIGHT_BLOCK = new Emitter(WHITE, LightEntry.defaultVolumetricRadianceScale());
    private static final Emitter LANTERN_FLAME = new Emitter(null, FLAME_VOLUMETRIC_SCALE);
    private static final Emitter PORTAL_GLOW = new Emitter(null, GLOW_VOLUMETRIC_SCALE);

    /**
     * A rule light's colour, or null for the block's curated colour, and its volumetric
     * radiance scale, which Remix multiplies into the fog it lights.
     */
    private record Emitter(LightEntry.ColorOverride color, float volumetricRadianceScale) {}

    // Shared beacon geometry and path-specific radiance tuning live in
    // BeaconBeam; the block_display lighthouse remains independent.
    // Beam colour is per-variant and lives on the block (SpotLightBlock.beamColor).

    private BlockLightDefaults() {}

    static Map<Identifier, BlockLightRuleManager.Rule> create() {
        LinkedHashMap<Identifier, BlockLightRuleManager.Rule> rules = new LinkedHashMap<>();
        // Built as locals, not static fields: a static field touching Blocks
        // would force the block registry to bootstrap just to load this class,
        // even for a caller that only wants a constant such as TORCH_COLOR.
        Set<Block> candles = candleBlocks();
        Set<Block> candleCakes = candleCakeBlocks();
        Set<Block> copperLanterns = Set.copyOf(Blocks.COPPER_LANTERN.asList());
        add(rules, "torch", exact(Blocks.TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, TORCH_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_torch", exact(Blocks.SOUL_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, SOUL_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_torch", exact(Blocks.COPPER_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, COPPER_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "wall_torch", exact(Blocks.WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, TORCH_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_wall_torch", exact(Blocks.SOUL_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, SOUL_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_wall_torch", exact(Blocks.COPPER_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, COPPER_FLAME)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_torch", state -> state.is(Blocks.REDSTONE_TORCH) && state.getValue(RedstoneTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, 0.16f, REDSTONE_GLOW)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_wall_torch", state -> state.is(Blocks.REDSTONE_WALL_TORCH) && state.getValue(RedstoneWallTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, REDSTONE_GLOW)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "small_amethyst_bud", exact(Blocks.SMALL_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 3.0f, 0.08f, 1)));
        add(rules, "medium_amethyst_bud", exact(Blocks.MEDIUM_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 4.0f, 0.10f, 2)));
        add(rules, "large_amethyst_bud", exact(Blocks.LARGE_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 5.0f, 0.13f, 4)));
        add(rules, "amethyst_cluster", exact(Blocks.AMETHYST_CLUSTER),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 7.0f, 0.18f, 5)));
        add(rules, "campfire", state -> state.is(Blocks.CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, CAMPFIRE_FLAME, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_campfire", state -> state.is(Blocks.SOUL_CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, SOUL_CAMPFIRE_FLAME, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "conduit", exact(Blocks.CONDUIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.1875f, CONDUIT_GLOW, null)));
        add(rules, "light", state -> state.is(Blocks.LIGHT) && state.getValue(BlockStateProperties.LEVEL) > 0,
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.2f, LIGHT_BLOCK, null)));
        add(rules, "candles", state -> candles.contains(state.getBlock()) && state.getValue(CandleBlock.LIT),
                BlockLightDefaults::candles);
        add(rules, "candle_cakes", state -> candleCakes.contains(state.getBlock()) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 1.0f, 0.5f, 0.08f, CANDLE_FLAME)));
        add(rules, "lantern", exact(Blocks.LANTERN), BlockLightDefaults::lantern,
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_lantern", exact(Blocks.SOUL_LANTERN), BlockLightDefaults::lantern,
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "copper_lanterns", state -> copperLanterns.contains(state.getBlock()), BlockLightDefaults::lantern,
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "fire", exact(Blocks.FIRE),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.4f, 0.5f, 0.25f, CAMPFIRE_FLAME, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_fire", exact(Blocks.SOUL_FIRE),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.4f, 0.5f, 0.25f, SOUL_CAMPFIRE_FLAME, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "nether_portal", exact(Blocks.NETHER_PORTAL), BlockLightDefaults::netherPortal,
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        BlockLightIntensityControls.validateBuiltInRules(rules.keySet());
        return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver) {
        add(rules, path, matcher, resolver, DEFAULT_AUTHORED_INTENSITY_MULTIPLIER);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        Identifier id = BlockLightRuleIds.builtIn(path);
        add(rules, id, matcher, resolver, authoredIntensityMultiplier);
    }

    private static void add(Map<Identifier, BlockLightRuleManager.Rule> rules,
                            Identifier id,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        rules.put(id, BlockLightRuleManager.rule(
                id,
                matcher,
                (ruleId, state, pos, sampler) -> resolver
                        .resolve(ruleId, state, pos, sampler)
                        .stream()
                        .map(entry -> entry.withAuthoredIntensityMultiplier(
                                authoredIntensityMultiplier))
                        .toList()));
    }

    private static Predicate<BlockState> exact(Block block) {
        return state -> state.is(block);
    }

    /**
     * Radiance for each of {@code count} one-sided rect lights of {@code area} that together
     * carry the power of a thin-sphere torch at {@code lightLevel}: a one-sided rect emits
     * pi A L, a sphere 4 pi r^2 * pi L.
     */
    private static float torchMatchedRectIntensity(int lightLevel, float area, int count) {
        float torchRadius = TORCH_RADIUS * THIN_SPHERE_RADIUS_SCALE;
        float torchIntensity = lightLevel * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL * THIN_SPHERE_INTENSITY_SCALE;
        return 4.0f * (float) Math.PI * torchRadius * torchRadius * torchIntensity / (area * count);
    }

    /** One rect light on each glass pane, facing out, so the light leaves the lantern body unlit. */
    private static List<LightEntry> lantern(Identifier id, BlockState state, BlockPos pos,
                                            BlockLightRuleManager.ModelSampler centroid) {
        float y = state.getValue(BlockStateProperties.HANGING)
                ? HANGING_LANTERN_BODY_CENTER_Y : STANDING_LANTERN_BODY_CENTER_Y;
        float intensity = torchMatchedRectIntensity(
                state.getLightEmission(), LANTERN_GLASS_WIDTH * LANTERN_GLASS_HEIGHT, 4);
        float out = LANTERN_HALF_WIDTH + SURFACE_LIGHT_OFFSET;
        BlockLightRuleManager.Vec3 up = new BlockLightRuleManager.Vec3(0, 1, 0);
        List<LightEntry> panes = new ArrayList<>(4);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockLightRuleManager.Vec3 normal = BlockLightRuleManager.Vec3.from(side);
            BlockLightRuleManager.Vec3 across = BlockLightRuleManager.Vec3.from(side.getClockWise());
            panes.add(BlockLightRuleManager.rect(id, panes.size(), state, pos,
                    0.5f + out * normal.x(), y, 0.5f + out * normal.z(),
                    across, LANTERN_GLASS_WIDTH, up, LANTERN_GLASS_HEIGHT, normal,
                    LANTERN_FLAME.color(), intensity, LANTERN_FLAME.volumetricRadianceScale()));
        }
        return panes;
    }

    /**
     * One block-sized rect light just outside each face of the 4/16-thick pane, together
     * power-matched to a torch at the portal's light level.
     */
    private static List<LightEntry> netherPortal(Identifier id, BlockState state, BlockPos pos,
                                                 BlockLightRuleManager.ModelSampler centroid) {
        boolean alongX = state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X;
        BlockLightRuleManager.Vec3 along = alongX
                ? new BlockLightRuleManager.Vec3(1, 0, 0) : new BlockLightRuleManager.Vec3(0, 0, 1);
        BlockLightRuleManager.Vec3 up = new BlockLightRuleManager.Vec3(0, 1, 0);
        float intensity = torchMatchedRectIntensity(state.getLightEmission(), 1.0f, 2);
        float out = PORTAL_HALF_THICKNESS + SURFACE_LIGHT_OFFSET;
        List<LightEntry> sides = new ArrayList<>(2);
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1.0f : -1.0f;
            BlockLightRuleManager.Vec3 normal = alongX
                    ? new BlockLightRuleManager.Vec3(0, 0, sign) : new BlockLightRuleManager.Vec3(sign, 0, 0);
            sides.add(BlockLightRuleManager.rect(id, side, state, pos,
                    0.5f + out * normal.x(), 0.5f, 0.5f + out * normal.z(),
                    along, 1.0f, up, 1.0f, normal,
                    PORTAL_GLOW.color(), intensity, PORTAL_GLOW.volumetricRadianceScale()));
        }
        return sides;
    }

    private static LightEntry sphere(Identifier id, int index, BlockState state, BlockPos pos,
                                     float x, float y, float z, float radius,
                                     Emitter emitter, Float intensity) {
        return BlockLightRuleManager.sphere(id, index, state, pos, x, y, z, radius,
                emitter.color(), intensity, emitter.volumetricRadianceScale());
    }

    private static LightEntry thinSphere(Identifier id, int index, BlockState state, BlockPos pos,
                                         float x, float y, float z, float radius,
                                         Emitter emitter) {
        float intensity = state.getLightEmission()
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * THIN_SPHERE_INTENSITY_SCALE;
        return sphere(id, index, state, pos, x, y, z, radius * THIN_SPHERE_RADIUS_SCALE, emitter, intensity);
    }

    private static LightEntry wallTorch(Identifier id, BlockState state, BlockPos pos, Emitter emitter) {
        Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
        return thinSphere(id, 0, state, pos,
                0.5f + 0.27f * facing.getStepX(),
                0.92f,
                0.5f + 0.27f * facing.getStepZ(),
                0.2f, emitter);
    }

    private static LightEntry endRod(Identifier id, BlockState state, BlockPos pos) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockLightRuleManager.Vec3 axis = BlockLightRuleManager.Vec3.fromAxis(facing.getAxis());
        return BlockLightRuleManager.cylinder(id, 0, state, pos,
                0.5f, 0.5f, 0.5f, 0.125f, axis, 1.0f, null, null);
    }

    private static LightEntry amethystCrystal(Identifier id, BlockState state, BlockPos pos,
                                              float heightPixels, float radius, int lightLevel) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        float halfHeight = heightPixels / 32.0f;
        float offsetFromCenter = 0.5f - halfHeight;
        float intensity = lightLevel
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * AMETHYST_INTENSITY_SCALE;
        return sphere(id, 0, state, pos,
                0.5f - facing.getStepX() * offsetFromCenter,
                0.5f - facing.getStepY() * offsetFromCenter,
                0.5f - facing.getStepZ() * offsetFromCenter,
                radius, AMETHYST_GLOW, intensity);
    }

    private static List<LightEntry> candles(Identifier id, BlockState state, BlockPos pos,
                                            BlockLightRuleManager.ModelSampler centroid) {
        List<LightEntry> entries = new ArrayList<>(state.getValue(CandleBlock.CANDLES));
        float[][] offsets = candleOffsets(state.getValue(CandleBlock.CANDLES));
        for (int i = 0; i < offsets.length; i++) {
            float[] o = offsets[i];
            entries.add(thinSphere(id, i, state, pos, o[0], o[1], o[2], 0.06f, CANDLE_FLAME));
        }
        return entries;
    }

    /** The plain candle and its sixteen dyed variants. */
    private static Set<Block> candleBlocks() {
        List<Block> blocks = new ArrayList<>(Blocks.DYED_CANDLE.asList());
        blocks.add(Blocks.CANDLE);
        return Set.copyOf(blocks);
    }

    /** The cake with a plain candle and the sixteen with a dyed one. */
    private static Set<Block> candleCakeBlocks() {
        List<Block> blocks = new ArrayList<>(Blocks.DYED_CANDLE_CAKE.asList());
        blocks.add(Blocks.CANDLE_CAKE);
        return Set.copyOf(blocks);
    }

    private static float[][] candleOffsets(int candles) {
        return switch (candles) {
            case 1 -> new float[][]{{0.5f, 0.5f, 0.5f}};
            case 2 -> new float[][]{{0.375f, 0.44f, 0.5f}, {0.625f, 0.5f, 0.44f}};
            case 3 -> new float[][]{{0.5f, 0.313f, 0.625f}, {0.375f, 0.44f, 0.5f}, {0.56f, 0.5f, 0.44f}};
            default -> new float[][]{{0.44f, 0.313f, 0.56f}, {0.625f, 0.44f, 0.56f}, {0.375f, 0.44f, 0.375f}, {0.56f, 0.5f, 0.375f}};
        };
    }
}
