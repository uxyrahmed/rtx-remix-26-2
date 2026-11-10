/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.Identifier;

class BuiltinEmissionTest {

    /** Centre of texel (col, row) of a 16x16 layout, in sprite UV space. */
    private static float texel(int index) {
        return (index + 0.5f) / 16f;
    }

    @Test
    void seaPickleBurnsAtTheTipAndFadesOutWithinTheSprout() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        float tip = m.factor(texel(1), texel(0));
        float second = m.factor(texel(2), texel(1));
        float third = m.factor(texel(13), texel(2));
        float fourth = m.factor(texel(14), texel(3));

        assertTrue(tip > 0.7f * BuiltinEmission.SEA_PICKLE_TIP_PEAK, "the tip row is hot: " + tip);
        assertTrue(tip > second && second > third && third > fourth, "monotonic fade down the sprout");
        assertTrue(second > 0.25f * tip, "the fade is gradual, not a single bright texel: " + second);
        assertTrue(fourth < 0.05f, "nearly out by the fourth row: " + fourth);
        assertEquals(0f, m.factor(texel(1), texel(4)), "the sprout's bottom row is dark");
    }

    @Test
    void seaPickleBodyStaysDark() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        // Body side faces read rows 5 to 11 across the whole width; the top face, rim and
        // opening read columns 4 to 12 of rows 1 to 5. None of it glows.
        for (int col = 0; col < 16; col++) {
            assertEquals(0f, m.factor(texel(col), texel(8)), "body column " + col);
        }
        for (int col = 4; col < 12; col++) {
            assertEquals(0f, m.factor(texel(col), texel(2)), "top face column " + col);
        }
    }

    @Test
    void onlyListedSpritesGetAMask() {
        assertSame(BuiltinEmission.SEA_PICKLE, BuiltinEmission.mask(Identifier.withDefaultNamespace("block/sea_pickle")));
        assertNull(BuiltinEmission.mask(Identifier.withDefaultNamespace("block/lantern")));
    }

    @Test
    void flamesAndLanternsGlowAtAShareOfTheirAuthoredEmission() {
        for (String sprite : new String[]{"torch", "copper_torch", "campfire_fire", "fire_0", "lantern",
                "oxidized_copper_lantern"}) {
            assertEquals(BuiltinEmission.LIT_EMITTER_GAIN,
                    BuiltinEmission.gain(Identifier.withDefaultNamespace("block/" + sprite)), sprite);
        }
    }

    @Test
    void lavaIsRaisedAndUnlistedSpritesKeepTheirAuthoredEmission() {
        assertEquals(2.0f, BuiltinEmission.gain(Identifier.withDefaultNamespace("block/lava_still")));
        assertEquals(2.0f, BuiltinEmission.gain(Identifier.withDefaultNamespace("block/lava_flow")));
        for (String sprite : new String[]{"glowstone", "sea_lantern", "pearlescent_froglight_side", "nether_portal", "end_rod"}) {
            assertEquals(1.0f, BuiltinEmission.gain(Identifier.withDefaultNamespace("block/" + sprite)), sprite);
        }
    }

    @Test
    void seaLanternsAndFroglightsGlowInTheirBlocksLightColour() {
        Map<String, String> blockOfSprite = Map.of(
                "sea_lantern", "sea_lantern",
                "ochre_froglight_side", "ochre_froglight",
                "ochre_froglight_top", "ochre_froglight",
                "verdant_froglight_side", "verdant_froglight",
                "verdant_froglight_top", "verdant_froglight",
                "pearlescent_froglight_side", "pearlescent_froglight",
                "pearlescent_froglight_top", "pearlescent_froglight");
        blockOfSprite.forEach((sprite, block) -> assertEquals(
                BuiltinEmission.Tint.of(BlockTextureManager.curatedLightColor(block)),
                BuiltinEmission.tint(Identifier.withDefaultNamespace("block/" + sprite)), sprite));
    }

    @Test
    void everyOtherSpriteKeepsTheColourItsPackGivesIt() {
        assertEquals(BuiltinEmission.Tint.NONE, BuiltinEmission.tint(Identifier.withDefaultNamespace("block/glowstone")));
        assertEquals(BuiltinEmission.Tint.NONE, BuiltinEmission.tint(Identifier.withDefaultNamespace("block/lantern")));
    }

    @Test
    void aTintGivesAGreyTexelTheColourWithoutBrighteningAnyChannel() {
        // The runtime decodes the emissive atlas with a 2.2 gamma, so a tinted grey texel must
        // decode to the colour's channel ratios, with its strongest channel keeping the grey's
        // value and the others below it. The colour need not be normalised to start with.
        float[] pink = {0.80f, 0.44f, 0.72f};
        BuiltinEmission.Tint tint = BuiltinEmission.Tint.of(pink);
        float grey = 0.6f;
        float r = decode(grey * tint.r()), g = decode(grey * tint.g()), b = decode(grey * tint.b());

        assertEquals(decode(grey), r, 1e-6f, "the strongest channel keeps the grey's value");
        assertEquals(pink[1] / pink[0], g / r, 1e-4f, "green to red");
        assertEquals(pink[2] / pink[0], b / r, 1e-4f, "blue to red");
    }

    @Test
    void aTintThatWouldBrightenOrInvertAChannelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BuiltinEmission.Tint(1.01f, 1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new BuiltinEmission.Tint(1f, -0.1f, 1f));
        assertThrows(IllegalArgumentException.class, () -> new BuiltinEmission.Tint(1f, 1f, Float.NaN));
    }

    private static float decode(float display) {
        return (float) Math.pow(display, BuiltinEmission.DISPLAY_GAMMA);
    }
}
