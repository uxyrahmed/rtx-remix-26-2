/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VividSkyTest {

    /** Direct-sun transmittance about five degrees above the horizon, deep red. */
    private static final float[] LOW_SUN = {0.096f, 0.0127f, 0.001f};

    @Test
    void theNaturalLookLeavesEverySkyInputExactlyAsShipped() {
        SkyStyle shipped = new SkyStyle();
        for (float sunHeight : new float[]{-1.0f, -0.1f, 0.0f, 0.2f, 1.0f}) {
            SkyStyle style = new SkyStyle();
            VividSky.styleAtmosphere(style, 0.0f, sunHeight);
            assertEquals(shipped.ozone, style.ozone);
            assertEquals(shipped.saturation, style.saturation);
            assertEquals(shipped.sunHalo, style.sunHalo);
            assertEquals(shipped.multipleScattering, style.multipleScattering);
        }
        assertArrayEquals(new float[]{0.85f * 0.06f, 0.9f * 0.06f, 0.06f},
                VividSky.moonSource(0.06f, 0.0f));
        float[] transmittance = LOW_SUN.clone();
        VividSky.styleSunTransmittance(transmittance, 0.0f);
        assertArrayEquals(LOW_SUN, transmittance);
    }

    @Test
    void theVividSkyIsMostSaturatedWhileTheSunIsLow() {
        float noon = VividSky.vividSaturation(1.0f);
        float sunset = VividSky.vividSaturation(0.03f);
        float midnight = VividSky.vividSaturation(-1.0f);
        assertEquals(VividSky.DAY_SATURATION, noon, 1e-6f);
        assertEquals(VividSky.GOLDEN_HOUR_SATURATION, sunset, 1e-6f);
        assertEquals(VividSky.NIGHT_SATURATION, midnight, 1e-6f);
        float previous = VividSky.vividSaturation(-1.0f);
        for (float height = -0.99f; height <= 1.0f; height += 0.01f) {
            float current = VividSky.vividSaturation(height);
            assertTrue(Math.abs(current - previous) < 0.05f,
                    "saturation must not jump across the day, at sun height " + height);
            previous = current;
        }
    }

    @Test
    void aLowVividSunIsGoldenAndBrighterRatherThanDimAndRed() {
        float[] vivid = LOW_SUN.clone();
        VividSky.styleSunTransmittance(vivid, 1.0f);
        assertTrue(vivid[0] > LOW_SUN[0], "a low Vivid sun keeps more of its light");
        float naturalGreenToRed = LOW_SUN[1] / LOW_SUN[0];
        float vividGreenToRed = vivid[1] / vivid[0];
        assertTrue(vividGreenToRed > 2.0f * naturalGreenToRed,
                "green rises against red, so the light turns golden: " + vividGreenToRed);
    }

    @Test
    void vividMoonlightIsBluerThanTheShippedMoon() {
        float[] natural = VividSky.moonSource(0.06f, 0.0f);
        float[] vivid = VividSky.moonSource(0.06f, 1.0f);
        assertTrue(vivid[2] / vivid[0] > natural[2] / natural[0]);
    }

    @Test
    void halfALookIsHalfwayBetweenTheTwo() {
        SkyStyle style = new SkyStyle();
        VividSky.styleAtmosphere(style, 0.5f, 1.0f);
        assertEquals(VividSky.OZONE / 2.0f, style.ozone, 1e-6f);
        assertEquals((1.0f + VividSky.DAY_SATURATION) / 2.0f, style.saturation, 1e-6f);
        assertEquals((SkyStyle.SHIPPED_SUN_HALO + VividSky.SUN_HALO) / 2.0f, style.sunHalo, 1e-5f);
        assertEquals((SkyStyle.SHIPPED_MULTIPLE_SCATTERING + VividSky.MULTIPLE_SCATTERING) / 2.0f,
                style.multipleScattering, 1e-6f);
        assertTrue(style.isValid());
    }
}
