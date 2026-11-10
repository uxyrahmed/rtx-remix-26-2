/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * The light and sky half of the Vivid look; its Remix options live in the
 * look's conf profiles under {@code confs/looks/vivid/}. The styling functions
 * take the look strength from {@code ConfLayerDriver.lookStrength()}, 0 for
 * the shipped look and 1 for Vivid, and leave the shipped values unchanged at
 * 0, so the look fades with its conf layers.
 *
 * <p>The look colours the light rather than the image. The local tonemapper
 * Remix uses has no colour grading, and a grade would tint torches with the
 * sun: warming the sunlight and cooling the moonlight leaves shadows lit by a
 * blue sky and lets block lights keep their own colour.
 */
public final class VividSky {

    /**
     * Sky colour saturation by day at full strength, about its luminance; 1 is
     * the physical sky. The sky is also the scene's fill light, so a strong
     * boost by day turns every shadow and every white surface blue.
     */
    static final float DAY_SATURATION = 1.2f;
    /**
     * Saturation while the sun is low, where the light is mostly direct and
     * golden and the sky's colour is the point of the scene.
     */
    static final float GOLDEN_HOUR_SATURATION = 1.6f;
    /** Saturation of the moonlit sky, which makes the night blue rather than grey. */
    static final float NIGHT_SATURATION = 1.45f;
    /**
     * Ozone absorption at full strength, as a fraction of Earth's. It barely
     * touches the noon sky, but sunlight grazing the ozone layer at twilight
     * loses its orange, which turns the zenith blue above a golden horizon
     * instead of a grey one. Earth's full layer also cools the sky around a
     * sun that has not yet set.
     */
    static final float OZONE = 0.35f;
    /**
     * Sun halo brightness at full strength. Lower than the shipped halo, which
     * fills the whole evening sky with white once the sun is low, but bright
     * enough to keep a golden glow around a setting sun.
     */
    static final float SUN_HALO = 7.0f;
    /**
     * Multiple scattering at full strength. Less of the hemisphere's mean is
     * added back than in the shipped sky, which keeps the horizon from turning
     * milky and leaves the sky's colours deeper.
     */
    static final float MULTIPLE_SCATTERING = 0.45f;
    /**
     * Exponent on the sunlight's atmospheric transmittance at full strength.
     * Below 1 it shortens the effective air mass, so a low sun is golden and
     * bright instead of dim and red.
     */
    static final float SUN_TRANSMITTANCE_EXPONENT = 0.5f;
    /** Warmth of direct sunlight at full strength, applied on top of the transmittance. */
    static final float[] SUN_TINT = {1.0f, 0.88f, 0.68f};
    /**
     * Direct sunlight brightness at full strength, relative to the sky. A sun
     * that outweighs the sky's fill leaves sunlit surfaces warm, shadows a
     * cooler blue, and the sky a deeper blue once exposure settles.
     */
    static final float SUN_BRIGHTNESS = 1.3f;
    /** The moon's colour in the shipped look, a faintly blue white. */
    private static final float[] SHIPPED_MOON_TINT = {0.85f, 0.9f, 1.0f};
    /** Moonlight colour at full strength, before brightness. */
    static final float[] MOON_TINT = {0.50f, 0.68f, 1.0f};
    /**
     * Moonlight brightness at full strength, relative to the shipped moon. The
     * bluer tint carries less luminance, so this keeps the night a little
     * darker than the shipped one rather than brighter.
     */
    static final float MOON_BRIGHTNESS = 1.15f;
    /**
     * Peak radiance of the aurora curtains at full strength, in the dome's
     * units: a few times the moonlit sky around them, so they read clearly
     * without lighting the world noticeably.
     */
    static final float AURORA_RADIANCE = 0.3f;
    /**
     * The aurora's animation period in ticks. The shader's frequencies are
     * whole cycles of it, so wrapping world time to it is seamless and the
     * time it receives stays small enough for float precision.
     */
    static final long AURORA_PERIOD_TICKS = 600L * 20L;
    /** How far star brightness dips at full strength when a star twinkles. */
    static final float TWINKLE_DEPTH = 0.65f;
    /** Stars twinkle in this many independent groups, each with its own flicker. */
    public static final int STAR_TWINKLE_GROUPS = 12;

    private VividSky() {
    }

    /**
     * Writes the atmosphere's art-direction inputs for a look strength and sun
     * height, the shipped sky at strength 0.
     *
     * @param style     the inputs to overwrite
     * @param look      the look strength
     * @param sunHeight the vertical component of the direction to the sun
     * @param gameTime  the world's synchronized game time in ticks, which
     *                  animates the aurora identically on every client
     */
    static void styleAtmosphere(SkyStyle style, float look, float sunHeight, long gameTime) {
        style.ozone = OZONE * clamp01(look);
        style.saturation = lerp(1.0f, vividSaturation(sunHeight), look);
        style.sunHalo = lerp(SkyStyle.SHIPPED_SUN_HALO, SUN_HALO, look);
        style.multipleScattering = lerp(SkyStyle.SHIPPED_MULTIPLE_SCATTERING, MULTIPLE_SCATTERING, look);
        style.auroraStrength = AURORA_RADIANCE * clamp01(look) * auroraVisibility(sunHeight);
        style.auroraTime = style.auroraStrength > 0.0f
                ? Math.floorMod(gameTime, AURORA_PERIOD_TICKS) / 20.0f
                : 0.0f;
    }

    /**
     * How much of the aurora shows for a sun height: none until the sun is
     * well below the horizon after dusk, all of it through the night, and
     * gone again before dawn brightens the sky.
     */
    static float auroraVisibility(float sunHeight) {
        return 1.0f - smoothstep(-0.28f, -0.12f, sunHeight);
    }

    /**
     * Brightness factor of one group of stars at a moment: 1 for the shipped
     * look, and for Vivid a flicker that dips each group independently, as
     * stars scintillate. A stateless function of synchronized world time, so
     * every client sees the same stars twinkle.
     *
     * @param look    the look strength
     * @param group   the star group, in {@code [0, STAR_TWINKLE_GROUPS)}
     * @param seconds world time in seconds, game time plus the frame's partial tick
     */
    public static float starTwinkle(float look, int group, double seconds) {
        float depth = TWINKLE_DEPTH * clamp01(look);
        if (depth == 0.0f) {
            return 1.0f;
        }
        double slow = 0.7 + 1.6 * groupHash(group, 1);
        double fast = 2.1 + 2.4 * groupHash(group, 2);
        double phase = 2.0 * Math.PI * groupHash(group, 3);
        double wave = 0.6 * Math.sin(2.0 * Math.PI * slow * seconds + phase)
                + 0.4 * Math.sin(2.0 * Math.PI * fast * seconds + 2.0 * phase);
        float flicker = (float) (0.5 + 0.5 * wave);
        return 1.0f - depth * flicker * flicker;
    }

    /** A fixed pseudo-random value in [0, 1) per star group and purpose. */
    private static double groupHash(int group, int salt) {
        long bits = (group * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL);
        bits ^= bits >>> 31;
        bits *= 0xBF58476D1CE4E5B9L;
        bits ^= bits >>> 29;
        return (bits >>> 11) * 0x1.0p-53;
    }

    /**
     * Vivid's sky saturation over the day: the day value with the sun high,
     * rising to the golden-hour value as the sun nears the horizon, and
     * settling on the night value once it is well below.
     */
    static float vividSaturation(float sunHeight) {
        float golden = 1.0f - smoothstep(0.05f, 0.35f, sunHeight);
        float night = 1.0f - smoothstep(-0.3f, -0.05f, sunHeight);
        float sunlit = lerp(DAY_SATURATION, GOLDEN_HOUR_SATURATION, golden);
        return lerp(sunlit, NIGHT_SATURATION, night);
    }

    /**
     * Restyles the factor the atmosphere applies to direct sunlight, in place:
     * a shorter effective air mass, then a warm tint and the look's sunlight
     * brightness.
     *
     * @param transmittance per-channel transmittance of the sunlight, in [0, 1]
     * @param look          the look strength
     */
    public static void styleSunTransmittance(float[] transmittance, float look) {
        float amount = clamp01(look);
        if (amount == 0.0f) {
            return;
        }
        float exponent = lerp(1.0f, SUN_TRANSMITTANCE_EXPONENT, amount);
        float brightness = lerp(1.0f, SUN_BRIGHTNESS, amount);
        for (int channel = 0; channel < 3; channel++) {
            float shortened = (float) Math.pow(transmittance[channel], exponent);
            transmittance[channel] = shortened * lerp(1.0f, SUN_TINT[channel], amount) * brightness;
        }
    }

    /**
     * The moon's source radiance: the shipped faintly blue white at strength
     * 0, a moonlit blue at 1.
     *
     * @param luminance the moon luminance the sun and moon settings resolve to
     * @param look      the look strength
     */
    public static float[] moonSource(float luminance, float look) {
        float amount = clamp01(look);
        float brightness = luminance * lerp(1.0f, MOON_BRIGHTNESS, amount);
        return new float[]{
                brightness * lerp(SHIPPED_MOON_TINT[0], MOON_TINT[0], amount),
                brightness * lerp(SHIPPED_MOON_TINT[1], MOON_TINT[1], amount),
                brightness * lerp(SHIPPED_MOON_TINT[2], MOON_TINT[2], amount)
        };
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float amount = clamp01((value - edge0) / (edge1 - edge0));
        return amount * amount * (3.0f - 2.0f * amount);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * clamp01(amount);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
