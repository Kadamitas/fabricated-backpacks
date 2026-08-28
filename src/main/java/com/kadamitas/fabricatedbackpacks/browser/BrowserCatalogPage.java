package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record BrowserCatalogPage(long epoch, int offset, int total, int undisplayedRecipes, boolean truncated,
                                  long buildNanos, List<BrowserRecipeEntry> entries) implements CustomPacketPayload {
    public static final int PAGE_SIZE = 64;
    public static final int MAX_ENTRIES = 100_000;
    public static final Type<BrowserCatalogPage> TYPE = new Type<>(BackpackRegistry.id("browser_catalog_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrowserCatalogPage> STREAM_CODEC = new StreamCodec<>() {
        @Override public BrowserCatalogPage decode(RegistryFriendlyByteBuf buffer) {
            long epoch = buffer.readVarLong();
            int offset = buffer.readVarInt(), total = buffer.readVarInt(), undisplayed = buffer.readVarInt();
            boolean truncated = buffer.readBoolean();
            long buildNanos = buffer.readVarLong();
            int count = buffer.readVarInt();
            checkBounds(epoch, offset, total, undisplayed, buildNanos, count);
            List<BrowserRecipeEntry> entries = new ArrayList<>(count);
            for (int index = 0; index < count; index++) entries.add(BrowserRecipeEntry.read(buffer));
            return new BrowserCatalogPage(epoch, offset, total, undisplayed, truncated, buildNanos, entries);
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, BrowserCatalogPage page) {
            buffer.writeVarLong(page.epoch);
            buffer.writeVarInt(page.offset);
            buffer.writeVarInt(page.total);
            buffer.writeVarInt(page.undisplayedRecipes);
            buffer.writeBoolean(page.truncated);
            buffer.writeVarLong(page.buildNanos);
            buffer.writeVarInt(page.entries.size());
            for (BrowserRecipeEntry entry : page.entries) entry.write(buffer);
        }
    };
    public BrowserCatalogPage {
        entries = List.copyOf(entries);
        checkBounds(epoch, offset, total, undisplayedRecipes, buildNanos, entries.size());
    }
    private static void checkBounds(long epoch, int offset, int total, int undisplayed, long buildNanos, int count) {
        if (epoch < 1 || offset < 0 || total < 0 || total > MAX_ENTRIES || offset > total || count < 0 || count > PAGE_SIZE
                || count > total - offset || count == 0 && offset < total || undisplayed < 0 || buildNanos < 0) {
            throw new IllegalArgumentException("Invalid browser catalog page bounds");
        }
    }
    public int nextOffset() { return offset + entries.size(); }
    @Override public Type<BrowserCatalogPage> type() { return TYPE; }
}
