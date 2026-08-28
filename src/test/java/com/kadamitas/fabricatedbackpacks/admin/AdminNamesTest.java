package com.kadamitas.fabricatedbackpacks.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminNamesTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "starter_pack", "kit-42", "0123456789"})
    void acceptsLocalFileStems(String name) { assertEquals(name, AdminNames.local(name)); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../escape", "a/b", "a\\b", ".", "..", "minecraft:kit", "UPPER", "two words", "-prefix", "C:\\escape", "name.json", "\u0000"})
    void rejectsPathsNamespacesAndAmbiguousNames(String name) { assertThrows(IllegalArgumentException.class, () -> AdminNames.local(name)); }

    @Test void exactLengthAndCanonicalUuidBoundaries() {
        assertTrue(AdminNames.isLocal("a".repeat(64)));
        assertFalse(AdminNames.isLocal("a".repeat(65)));
        assertTrue(AdminNames.isIdentity(UUID.randomUUID().toString()));
        assertFalse(AdminNames.isIdentity("1-1-1-1-1"));
        assertFalse(AdminNames.isIdentity("not-a-uuid"));
        assertFalse(AdminNames.isIdentity(null));
    }
}
