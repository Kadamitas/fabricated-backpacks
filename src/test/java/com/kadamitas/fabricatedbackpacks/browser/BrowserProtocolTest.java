package com.kadamitas.fabricatedbackpacks.browser;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserProtocolTest {
    @Test void requestCursorsAreBoundedAndRoundTripWithoutItemData() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogRequest(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogRequest(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogRequest(0, 100_001));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BrowserCatalogRequest expected = new BrowserCatalogRequest(42, 100_000);
            BrowserCatalogRequest.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, BrowserCatalogRequest.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void catalogBoundsRejectMalformedPages() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(0, 0, 0, 0, false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(1, -1, 0, 0, false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(1, 4, 3, 0, false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(1, 0, 100_001, 0, false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(1, 0, 0, 0, false, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BrowserCatalogPage(1, 0, 1, 0, false, 0, List.of()));
        assertDoesNotThrow(() -> new BrowserCatalogPage(1, 0, 0, 0, false, 0, List.of()));
    }

    @Test void emptyCatalogRoundTripsAndRejectsOversizedEntryCountBeforeAllocation() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BrowserCatalogPage expected = new BrowserCatalogPage(7, 0, 0, 3, false, 20, List.of());
            BrowserCatalogPage.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, BrowserCatalogPage.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeVarLong(1);
            buffer.writeVarInt(0);
            buffer.writeVarInt(65);
            buffer.writeVarInt(0);
            buffer.writeBoolean(false);
            buffer.writeVarLong(0);
            buffer.writeVarInt(65);
            assertThrows(IllegalArgumentException.class, () -> BrowserCatalogPage.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void transferRequestCarriesIdentitiesAndABoundedMaximumFlag() {
        Identifier recipe = Identifier.fromNamespaceAndPath("minecraft", "oak_planks");
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferRequest(0, 1, recipe, 1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferRequest(1, -1, recipe, 1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferRequest(1, 1, recipe, 0));
        assertThrows(NullPointerException.class, () -> new BrowserTransferRequest(1, 1, null, 1));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (boolean maximum : new boolean[] {false, true}) {
                BrowserTransferRequest expected = new BrowserTransferRequest(2, 15, recipe, 32, maximum);
                BrowserTransferRequest.STREAM_CODEC.encode(buffer, expected);
                assertEquals(expected, BrowserTransferRequest.STREAM_CODEC.decode(buffer));
            }
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void transferResponsesCarryCorrelationAndBoundedFeedback() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferResult(0, false, "rejected"));
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferResult(1, false, "x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserTransferResult(1, false, null));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BrowserTransferResult expected = new BrowserTransferResult(43, false, "browser.fabricated_backpacks.invalid_menu");
            BrowserTransferResult.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, BrowserTransferResult.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void contextMessagesOnlyDescribeAnExistingServerMenu() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserContextRequest(-1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserContext(-1, true, false));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BrowserContextRequest request = new BrowserContextRequest(38);
            BrowserContextRequest.STREAM_CODEC.encode(buffer, request);
            assertEquals(request, BrowserContextRequest.STREAM_CODEC.decode(buffer));
            for (BrowserWorkstation workstation : BrowserWorkstation.values()) {
                BrowserContext response = new BrowserContext(38, workstation, true);
                BrowserContext.STREAM_CODEC.encode(buffer, response);
                assertEquals(response, BrowserContext.STREAM_CODEC.decode(buffer));
            }
            assertEquals(0, buffer.readableBytes());
            buffer.writeVarInt(38);
            buffer.writeVarInt(BrowserWorkstation.values().length);
            buffer.writeBoolean(false);
            assertThrows(IllegalArgumentException.class, () -> BrowserContext.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void contextRequiresAnExactSupportedRecipeType() {
        assertTrue(BrowserWorkstation.SMELTING.accepts(Identifier.withDefaultNamespace("smelting")));
        assertFalse(BrowserWorkstation.SMELTING.accepts(Identifier.withDefaultNamespace("blasting")));
        assertFalse(BrowserWorkstation.SMOKING.accepts(Identifier.fromNamespaceAndPath("custom", "smoking")));
        assertFalse(BrowserWorkstation.NONE.accepts(Identifier.withDefaultNamespace("crafting")));
        assertThrows(IllegalArgumentException.class, () -> BrowserWorkstation.fromId(-1));
    }
}
