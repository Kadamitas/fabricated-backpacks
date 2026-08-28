package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;

/** Lets automation share the live inventory while a portable workstation is open. */
public interface BackpackSessionMenu { BagInventory backpack(); }
