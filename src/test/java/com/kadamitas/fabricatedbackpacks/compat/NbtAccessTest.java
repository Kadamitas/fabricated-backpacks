package com.kadamitas.fabricatedbackpacks.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NbtAccessTest {
    @Test void missingAndWrongTypesKeepExplicitDefaultsWithoutWriting() {
        CompoundTag tag = new CompoundTag();
        tag.putString("wrong", "4");
        CompoundTag before = tag.copy();
        for (String key : new String[]{"missing", "wrong"}) {
            assertEquals(7, NbtAccess.getIntOr(tag, key, 7));
            assertEquals(9L, NbtAccess.getLongOr(tag, key, 9));
            assertEquals(1.25, NbtAccess.getDoubleOr(tag, key, 1.25));
            assertTrue(NbtAccess.getBooleanOr(tag, key, true));
            assertTrue(NbtAccess.getCompound(tag, key).isEmpty());
            assertTrue(NbtAccess.getList(tag, key).isEmpty());
        }
        assertEquals("fallback", NbtAccess.getStringOr(tag, "missing", "fallback"));
        assertEquals("4", NbtAccess.getStringOr(tag, "wrong", "fallback"));
        assertEquals(before, tag);
    }

    @Test void zeroIsAStoredValueAndNumericNativeCoercionsAreRetained() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("zero", 0);
        tag.putShort("short", (short) 12);
        tag.putDouble("fraction", 6.75);
        assertEquals(0, NbtAccess.getIntOr(tag, "zero", -1));
        assertFalse(NbtAccess.getBooleanOr(tag, "zero", true));
        assertEquals(12L, NbtAccess.getLongOr(tag, "short", -1));
        assertEquals(6, NbtAccess.getIntOr(tag, "fraction", -1));
        assertEquals(6.75F, NbtAccess.getFloatOr(tag, "fraction", -1));
        assertEquals("default", NbtAccess.getStringOr(tag, "short", "default"));
    }

    @Test void optionalArraysDistinguishEmptyFromAbsentAndRejectDifferentArrayTypes() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("empty", new int[0]);
        tag.putIntArray("slots", new int[]{0, 63, 255});
        tag.putByteArray("bytes", new byte[]{1, 2});
        tag.putLongArray("longs", new long[]{Long.MAX_VALUE});
        assertTrue(NbtAccess.getIntArray(tag, "empty").isPresent());
        assertTrue(NbtAccess.getIntArray(tag, "absent").isEmpty());
        assertTrue(NbtAccess.getIntArray(tag, "bytes").isEmpty());
        assertArrayEquals(new int[]{0, 63, 255}, NbtAccess.getIntArray(tag, "slots").orElseThrow());
        assertArrayEquals(new byte[]{1, 2}, NbtAccess.getByteArray(tag, "bytes").orElseThrow());
        assertArrayEquals(new long[]{Long.MAX_VALUE}, NbtAccess.getLongArray(tag, "longs").orElseThrow());
    }

    @Test void untypedListsPreserveBothStringsAndCompoundRows() {
        CompoundTag tag = new CompoundTag();
        ListTag strings = new ListTag(); strings.add(StringTag.valueOf("one"));
        ListTag numbers = new ListTag(); numbers.add(IntTag.valueOf(3));
        ListTag compounds = new ListTag(); compounds.add(new CompoundTag());
        tag.put("strings", strings); tag.put("numbers", numbers); tag.put("rows", compounds);
        assertSame(strings, NbtAccess.getListOrEmpty(tag, "strings"));
        assertSame(numbers, NbtAccess.getList(tag, "numbers").orElseThrow());
        assertSame(compounds.getCompound(0), NbtAccess.getCompoundOrEmpty(compounds, 0));
        assertTrue(NbtAccess.getListOrEmpty(tag, "missing").isEmpty());
        assertFalse(tag.contains("missing"));
    }
}
