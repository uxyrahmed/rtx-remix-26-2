/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * How brightly a sprite glows relative to what the resource pack authors, set by whether
 * the block carries its own analytic light. A block with a built-in light rule only has to
 * read as glowing, because the rule lights the room; a block without one, such as lava or a
 * sea pickle, lights the scene only through its emissive texels.
 *
 * <p>{@link #gain} scales a sprite's authored LabPBR emission ({@code _s} alpha), applied
 * identically on the atlas build and on every animated frame so a flickering sprite does
 * not change brightness between the two paths. A factor never rises above full emission.
 * {@link #mask} supplies emission for a sprite the pack authors none for, as a factor over
 * the sprite's own UV space; an authored {@code _s} with emission always wins over it, so a
 * pack can replace these at any time.
 */
public final class BuiltinEmission {
    /**
     * Surfaces of flames and lanterns, whose built-in light rule ({@code BlockLightDefaults})
     * lights the scene without lighting the block itself. At this share of their authored
     * emission a fully emissive texel stays a few times brighter than the walls its light
     * reaches, which the tonemapper holds without clipping to white even at night's capped
     * exposure, so the flame or the lantern glass keeps its texture.
     */
    static final float LIT_EMITTER_GAIN = 0.4f;
    private static final List<String> LIT_EMITTER_SPRITES = List.of(
            "torch", "soul_torch", "copper_torch", "redstone_torch",
            "campfire_fire", "soul_campfire_fire", "campfire_log_lit", "soul_campfire_log_lit",
            "fire_0", "fire_1", "soul_fire_0", "soul_fire_1",
            "lantern", "soul_lantern", "copper_lantern", "exposed_copper_lantern",
            "weathered_copper_lantern", "oxidized_copper_lantern");
    /**
     * Lava: a pool can hold thousands of blocks, too many for analytic lights, so its surface
     * is its light. A pack that authors lava below full emission is raised toward it.
     */
    private static final float LAVA_GAIN = 2.0f;

    private static final Map<Identifier, Float> GAIN = gains();

    /** Emission factor at a point of a sprite, {@code u} and {@code v} in [0, 1) across the sprite. */
    @FunctionalInterface
    public interface Mask {
        float factor(float u, float v);
    }

    /**
     * Sea pickle, laid out as vanilla's {@code sea_pickle} model maps its 16x16 texture:
     * the sprout quads read columns 1 to 3 and 13 to 15 of rows 0 to 5, tip at row 0.
     * Only the waterlogged models have sprout quads, so a dry pickle shows no glow at all
     * and keeps its regular texture, as its luminance of zero says it should.
     *
     * <p>The tip burns at full emission and falls off quadratically over the top rows,
     * the way a lit candle's wax glows hottest at the wick and dims within a few
     * millimetres: row factors 0.77, 0.39, 0.14, 0.02, then nothing. The body stays dark.
     * A few bright texels per sprout read as a point of light on the sea floor around
     * coral without turning the pickle into a lamp.
     */
    static final float SEA_PICKLE_TIP_PEAK = 1.0f;
    static final float SEA_PICKLE_FADE_ROWS = 4.0f;

    static final Mask SEA_PICKLE = (u, v) -> {
        float col = u * 16f, row = v * 16f;
        boolean sprout = (col >= 1f && col < 3f) || (col >= 13f && col < 15f);
        if (!sprout || row >= SEA_PICKLE_FADE_ROWS) {
            return 0f;
        }
        float fromTip = 1f - row / SEA_PICKLE_FADE_ROWS;
        return SEA_PICKLE_TIP_PEAK * fromTip * fromTip;
    };

    private static final Map<Identifier, Mask> MASK = Map.of(
            Identifier.withDefaultNamespace("block/sea_pickle"), SEA_PICKLE);

    private BuiltinEmission() {
    }

    private static Map<Identifier, Float> gains() {
        Map<Identifier, Float> gains = new HashMap<>();
        for (String sprite : LIT_EMITTER_SPRITES) {
            gains.put(Identifier.withDefaultNamespace("block/" + sprite), LIT_EMITTER_GAIN);
        }
        gains.put(Identifier.withDefaultNamespace("block/lava_still"), LAVA_GAIN);
        gains.put(Identifier.withDefaultNamespace("block/lava_flow"), LAVA_GAIN);
        return Map.copyOf(gains);
    }

    /** Multiplier for the sprite's authored emission factor; 1 for every sprite not listed. */
    public static float gain(Identifier spriteId) {
        Float gain = GAIN.get(spriteId);
        return gain != null ? gain : 1.0f;
    }

    /** Emission for a sprite the pack leaves unauthored, or null when the mod adds none. */
    public static Mask mask(Identifier spriteId) {
        return MASK.get(spriteId);
    }
}
