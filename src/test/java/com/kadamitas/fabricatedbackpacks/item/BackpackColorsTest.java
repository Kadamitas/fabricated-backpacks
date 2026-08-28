package com.kadamitas.fabricatedbackpacks.item;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackpackColorsTest {
    @Test void independentRgbLayersRoundTripIncludingBlackAndWhite() {
        for (BackpackColors colors : new BackpackColors[]{new BackpackColors(0, 0xFFFFFF),
                new BackpackColors(0x336699, 0xFF0022), new BackpackColors(BackpackColors.DEFAULT_BODY, BackpackColors.DEFAULT_TRIM)}) {
            var json = BackpackColors.CODEC.encodeStart(JsonOps.INSTANCE, colors).getOrThrow();
            assertEquals(colors, BackpackColors.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        }
    }
    @Test void invalidRgbAndMissingLayersFailRatherThanTruncate() {
        assertThrows(IllegalArgumentException.class, () -> new BackpackColors(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BackpackColors(0, 0x1000000));
        for (String json : new String[]{"{\"body\":-1,\"trim\":0}", "{\"body\":0,\"trim\":16777216}", "{\"body\":0}"})
            assertTrue(BackpackColors.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent());
    }
}
