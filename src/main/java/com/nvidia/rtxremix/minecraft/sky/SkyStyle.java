/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * The art-direction inputs the atmosphere shader takes beside its physical
 * ones. A new instance holds the shipped values, which leave the sky
 * physical; {@link VividSky#styleAtmosphere} overwrites them each frame, so
 * one instance is reused rather than allocated per frame.
 */
final class SkyStyle {
    /** Brightness of the sun's Mie halo over single scattering in the shipped sky. */
    static final float SHIPPED_SUN_HALO = 10.35f;
    /** Share of the hemisphere's mean radiance added back as multiple scattering in the shipped sky. */
    static final float SHIPPED_MULTIPLE_SCATTERING = 0.65f;

    /** Ozone absorption as a fraction of Earth's layer; 0 leaves the sky without one. */
    float ozone;
    /** Sky colour saturation about its luminance; 1 leaves the physical colour. */
    float saturation = 1.0f;
    /** Brightness of the sun's Mie halo over single scattering. */
    float sunHalo = SHIPPED_SUN_HALO;
    /** Share of the hemisphere's mean radiance added back as multiple scattering. */
    float multipleScattering = SHIPPED_MULTIPLE_SCATTERING;

    /** Whether every input is finite and non-negative, which the shader needs. */
    boolean isValid() {
        return isFiniteNonNegative(ozone) && isFiniteNonNegative(saturation)
                && isFiniteNonNegative(sunHalo) && isFiniteNonNegative(multipleScattering);
    }

    private static boolean isFiniteNonNegative(float value) {
        return Float.isFinite(value) && value >= 0.0f;
    }
}
