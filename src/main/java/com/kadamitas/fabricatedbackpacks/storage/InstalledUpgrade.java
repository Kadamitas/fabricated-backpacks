package com.kadamitas.fabricatedbackpacks.storage;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import net.minecraft.world.item.ItemStack;

public record InstalledUpgrade(int slot, UpgradeKind kind, ItemStack stack) {}
