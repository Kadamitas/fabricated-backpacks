package com.kadamitas.fabricatedbackpacks.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/** Typed, fallback-preserving NBT reads for the 1.21.1 native tag API. */
public final class NbtAccess {
    private NbtAccess() {}

    public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getBoolean(key) : fallback; }
    public static byte getByteOr(CompoundTag tag, String key, byte fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getByte(key) : fallback; }
    public static short getShortOr(CompoundTag tag, String key, short fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getShort(key) : fallback; }
    public static int getIntOr(CompoundTag tag, String key, int fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : fallback; }
    public static long getLongOr(CompoundTag tag, String key, long fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getLong(key) : fallback; }
    public static float getFloatOr(CompoundTag tag, String key, float fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getFloat(key) : fallback; }
    public static double getDoubleOr(CompoundTag tag, String key, double fallback) { return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getDouble(key) : fallback; }
    public static String getStringOr(CompoundTag tag, String key, String fallback) { return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback; }
    public static Optional<CompoundTag> getCompound(CompoundTag tag, String key) { return tag.get(key) instanceof CompoundTag value ? Optional.of(value) : Optional.empty(); }
    public static Optional<ListTag> getList(CompoundTag tag, String key) { return tag.get(key) instanceof ListTag value ? Optional.of(value) : Optional.empty(); }
    public static Optional<byte[]> getByteArray(CompoundTag tag, String key) { return tag.contains(key, Tag.TAG_BYTE_ARRAY) ? Optional.of(tag.getByteArray(key)) : Optional.empty(); }
    public static Optional<int[]> getIntArray(CompoundTag tag, String key) { return tag.contains(key, Tag.TAG_INT_ARRAY) ? Optional.of(tag.getIntArray(key)) : Optional.empty(); }
    public static Optional<long[]> getLongArray(CompoundTag tag, String key) { return tag.contains(key, Tag.TAG_LONG_ARRAY) ? Optional.of(tag.getLongArray(key)) : Optional.empty(); }
    public static CompoundTag getCompoundOrEmpty(CompoundTag tag, String key) { return tag.getCompound(key); }
    public static ListTag getListOrEmpty(CompoundTag tag, String key) { return getList(tag, key).orElseGet(ListTag::new); }
    public static CompoundTag getCompoundOrEmpty(ListTag tag, int index) { return tag.getCompound(index); }
}
