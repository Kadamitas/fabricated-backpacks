package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable registry identities in nine stable ghost positions; entries never own resources. */
public record ConduitFilter(ConduitFilterMode mode, Map<Integer, ResourceLocation> entries) {
    public static final int SLOT_COUNT = 9;
    public static final int MAX_IDENTIFIER_LENGTH = 256;
    public static final ConduitFilter EMPTY = new ConduitFilter(ConduitFilterMode.OFF, Map.of());
    public static final ConduitFilter DENY_ALL = new ConduitFilter(ConduitFilterMode.ALLOW, Map.of());
    private static final Codec<ResourceLocation> IDENTIFIER_CODEC = ResourceLocation.CODEC.validate(id ->
            id.toString().length() <= MAX_IDENTIFIER_LENGTH ? DataResult.success(id)
                    : DataResult.error(() -> "Conduit filter identifier is too long"));
    private record Entry(int slot, ResourceLocation id) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, SLOT_COUNT - 1).fieldOf("slot").forGetter(Entry::slot),
                IDENTIFIER_CODEC.fieldOf("id").forGetter(Entry::id)
        ).apply(instance, Entry::new));
    }
    private record Encoded(ConduitFilterMode mode, List<Entry> entries) {}
    private static final Codec<ConduitFilterMode> MODE_CODEC = Codec.STRING.comapFlatMap(value -> {
        for (ConduitFilterMode mode : ConduitFilterMode.values())
            if (mode.name().equalsIgnoreCase(value)) return DataResult.success(mode);
        return DataResult.error(() -> "Unknown conduit filter mode");
    }, mode -> mode.name().toLowerCase(java.util.Locale.ROOT));
    private static final Codec<Encoded> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
            MODE_CODEC.fieldOf("mode").forGetter(Encoded::mode),
            Entry.CODEC.listOf(0, SLOT_COUNT).fieldOf("entries").forGetter(Encoded::entries)
    ).apply(instance, Encoded::new));
    public static final Codec<ConduitFilter> CODEC = FIELDS.comapFlatMap(encoded -> {
        Map<Integer, ResourceLocation> entries = new LinkedHashMap<>();
        for (Entry entry : encoded.entries())
            if (entries.put(entry.slot(), entry.id()) != null)
                return DataResult.error(() -> "Duplicate conduit filter slot");
        try { return DataResult.success(new ConduitFilter(encoded.mode(), entries)); }
        catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
    }, filter -> new Encoded(filter.mode(), filter.entries().entrySet().stream()
            .map(entry -> new Entry(entry.getKey(), entry.getValue())).toList()));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConduitFilter> STREAM_CODEC = new StreamCodec<>() {
        @Override public ConduitFilter decode(RegistryFriendlyByteBuf buffer) {
            int mode = buffer.readUnsignedByte();
            int count = buffer.readUnsignedByte();
            if (mode >= ConduitFilterMode.values().length || count > SLOT_COUNT)
                throw new DecoderException("Invalid conduit filter bounds");
            Map<Integer, ResourceLocation> entries = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                int slot = buffer.readUnsignedByte();
                if (slot >= SLOT_COUNT || entries.containsKey(slot))
                    throw new DecoderException("Invalid or duplicate conduit filter slot");
                entries.put(slot, ResourceLocation.parse(buffer.readUtf(MAX_IDENTIFIER_LENGTH)));
            }
            try { return new ConduitFilter(ConduitFilterMode.values()[mode], entries); }
            catch (IllegalArgumentException failure) { throw new DecoderException(failure); }
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, ConduitFilter filter) {
            buffer.writeByte(filter.mode().ordinal());
            buffer.writeByte(filter.entries().size());
            filter.entries().forEach((slot, id) -> {
                buffer.writeByte(slot);
                buffer.writeUtf(id.toString(), MAX_IDENTIFIER_LENGTH);
            });
        }
    };

    public ConduitFilter {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > SLOT_COUNT) throw new IllegalArgumentException("Too many conduit filter entries");
        Map<Integer, ResourceLocation> copy = new TreeMap<>();
        var identities = new HashSet<ResourceLocation>();
        entries.forEach((slot, id) -> {
            checkSlot(Objects.requireNonNull(slot, "slot"));
            Objects.requireNonNull(id, "identifier");
            if (id.toString().length() > MAX_IDENTIFIER_LENGTH)
                throw new IllegalArgumentException("Conduit filter identifier is too long");
            if (!identities.add(id)) throw new IllegalArgumentException("Duplicate conduit filter identity");
            copy.put(slot, id);
        });
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    public Optional<ResourceLocation> entry(int slot) {
        checkSlot(slot);
        return Optional.ofNullable(entries.get(slot));
    }
    public ConduitFilter withMode(ConduitFilterMode next) {
        return mode == next ? this : new ConduitFilter(next, entries);
    }
    public ConduitFilter withEntry(int slot, ResourceLocation id) {
        checkSlot(slot);
        if (Objects.equals(entries.get(slot), Objects.requireNonNull(id, "identifier"))) return this;
        Map<Integer, ResourceLocation> changed = new LinkedHashMap<>(entries);
        changed.put(slot, id);
        return new ConduitFilter(mode, changed);
    }
    public ConduitFilter withoutEntry(int slot) {
        checkSlot(slot);
        if (!entries.containsKey(slot)) return this;
        Map<Integer, ResourceLocation> changed = new LinkedHashMap<>(entries);
        changed.remove(slot);
        return new ConduitFilter(mode, changed);
    }
    public boolean matches(ResourceLocation id) {
        Objects.requireNonNull(id, "identifier");
        return switch (mode) {
            case OFF -> true;
            case ALLOW -> entries.containsValue(id);
            case BLOCK -> !entries.containsValue(id);
        };
    }
    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) throw new IllegalArgumentException("Invalid conduit filter slot");
    }
}
