package com.kadamitas.fabricatedbackpacks.mixin;

import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CraftingMenu.class)
public interface CraftingMenuAccess {
    @Accessor("craftSlots") CraftingContainer fabricated$craftSlots();
    @Accessor("resultSlots") ResultContainer fabricated$resultSlots();
    @Accessor("player") Player fabricated$player();
}
