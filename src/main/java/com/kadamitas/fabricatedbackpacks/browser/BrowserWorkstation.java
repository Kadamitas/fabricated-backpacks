package com.kadamitas.fabricatedbackpacks.browser;

import net.minecraft.resources.Identifier;

/** A small protocol vocabulary for destinations whose input layout the server understands. */
public enum BrowserWorkstation {
    NONE(null), CRAFTING("crafting"), STONECUTTER("stonecutting"), SMITHING("smithing"),
    SMELTING("smelting"), SMOKING("smoking"), BLASTING("blasting");

    private final Identifier category;

    BrowserWorkstation(String category) {
        this.category = category == null ? null : Identifier.withDefaultNamespace(category);
    }

    public boolean accepts(Identifier category) { return this.category != null && this.category.equals(category); }

    public static BrowserWorkstation fromId(int id) {
        if (id < 0 || id >= values().length) throw new IllegalArgumentException("Unknown browser workstation");
        return values()[id];
    }
}
