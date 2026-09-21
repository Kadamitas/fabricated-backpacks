package com.kadamitas.fabricatedbackpacks.platform.transfer;

/**
 * A container owned by this mod. Its transactional slot access journals through the mod's
 * participant ledger so it unwinds in the same last-in, first-out order as every other
 * participant; foreign containers keep NeoForge's own wrappers.
 */
public interface OwnedContainer {}
