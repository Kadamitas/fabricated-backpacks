package com.kadamitas.fabricatedbackpacks.browser;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserProtocolTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    @Test void fuelHasItsOwnBoundWithoutRelaxingIngredientAndResultLimits() {
        assertEquals(256, BrowserRecipeEntry.MAX_OPTIONS);
        assertEquals(1_024, BrowserRecipeEntry.MAX_FUEL_OPTIONS);
        List<ItemStack> alternatives = Collections.nCopies(BrowserRecipeEntry.MAX_OPTIONS, new ItemStack(Items.RAW_IRON));
        List<ItemStack> fuels = Collections.nCopies(BrowserRecipeEntry.MAX_FUEL_OPTIONS, new ItemStack(Items.BLAZE_ROD));
        List<ItemStack> results = Collections.nCopies(BrowserRecipeEntry.MAX_OPTIONS, new ItemStack(Items.IRON_INGOT));
        BrowserRecipeEntry expected = presentation(List.of(alternatives), fuels, results);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            expected.write(buffer);
            assertTrue(buffer.readableBytes() <= 12_000, "The larger ordinary-fuel collection still fits the existing entry byte budget");
            byte[] wire = ByteBufUtil.getBytes(buffer);
            BrowserRecipeEntry decoded = BrowserRecipeEntry.read(buffer);
            assertEquals(BrowserRecipeEntry.MAX_OPTIONS, decoded.ingredients().getFirst().size());
            assertEquals(BrowserRecipeEntry.MAX_FUEL_OPTIONS, decoded.fuel().size());
            assertEquals(BrowserRecipeEntry.MAX_OPTIONS, decoded.results().size());
            assertTrue(decoded.fuel().stream().allMatch(stack -> ItemStack.matches(stack, fuels.getFirst())));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            decoded.write(buffer);
            assertArrayEquals(wire, ByteBufUtil.getBytes(buffer), "Every bounded alternative survives the native wire codec");
        } finally { buffer.release(); }
        List<ItemStack> tooManyOptions = Collections.nCopies(BrowserRecipeEntry.MAX_OPTIONS + 1, ItemStack.EMPTY);
        List<ItemStack> tooManyFuels = Collections.nCopies(BrowserRecipeEntry.MAX_FUEL_OPTIONS + 1, ItemStack.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> presentation(List.of(tooManyOptions), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> presentation(List.of(), List.of(), tooManyOptions));
        assertThrows(IllegalArgumentException.class, () -> presentation(List.of(), tooManyFuels, List.of()));
    }

    @Test void recipeOptionDecodersRejectOutOfBoundsCountsBeforeReadingAnyStacks() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int count : new int[] {-1, BrowserRecipeEntry.MAX_FUEL_OPTIONS + 1, Integer.MAX_VALUE}) {
                buffer.clear();
                writePresentationPrefix(buffer, 0);
                buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> BrowserRecipeEntry.read(buffer));
                assertEquals(0, buffer.readableBytes());
            }
            for (int field = 0; field < 3; field++) {
                buffer.clear();
                writePresentationPrefix(buffer, field == 0 ? 1 : 0);
                if (field > 0) buffer.writeVarInt(0); // Fuel precedes results and stations.
                if (field > 1) buffer.writeVarInt(0); // Results precede stations.
                buffer.writeVarInt(BrowserRecipeEntry.MAX_OPTIONS + 1);
                assertThrows(IllegalArgumentException.class, () -> BrowserRecipeEntry.read(buffer));
                assertEquals(0, buffer.readableBytes());
            }
        } finally { buffer.release(); }
    }

    @Test void transferRequestCarriesIdentitiesAndABoundedMaximumFlag() {
        ResourceLocation recipe = ResourceLocation.fromNamespaceAndPath("minecraft", "oak_planks");
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
        assertTrue(BrowserWorkstation.SMELTING.accepts(ResourceLocation.withDefaultNamespace("smelting")));
        assertFalse(BrowserWorkstation.SMELTING.accepts(ResourceLocation.withDefaultNamespace("blasting")));
        assertFalse(BrowserWorkstation.SMOKING.accepts(ResourceLocation.fromNamespaceAndPath("custom", "smoking")));
        assertFalse(BrowserWorkstation.NONE.accepts(ResourceLocation.withDefaultNamespace("crafting")));
        assertThrows(IllegalArgumentException.class, () -> BrowserWorkstation.fromId(-1));
    }

    private static BrowserRecipeEntry presentation(List<List<ItemStack>> ingredients, List<ItemStack> fuels, List<ItemStack> results) {
        return new BrowserRecipeEntry(ResourceLocation.withDefaultNamespace("test_recipe"), ResourceLocation.withDefaultNamespace("smelting"),
                BrowserRecipeEntry.Layout.FURNACE, 1, 1, ingredients, fuels, results,
                List.of(new ItemStack(Items.FURNACE)), 200, 0.7F, false);
    }

    private static void writePresentationPrefix(RegistryFriendlyByteBuf buffer, int groups) {
        buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("test_recipe"));
        buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("smelting"));
        buffer.writeEnum(BrowserRecipeEntry.Layout.FURNACE);
        buffer.writeVarInt(1);
        buffer.writeVarInt(1);
        buffer.writeVarInt(groups);
    }
}
