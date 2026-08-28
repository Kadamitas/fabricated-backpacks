package com.kadamitas.fabricatedbackpacks.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("hoveredSlot") Slot fabricatedBackpacks$hoveredSlot();
    @Accessor("leftPos") int fabricatedBackpacks$left();
    @Accessor("topPos") int fabricatedBackpacks$top();
    @Accessor("lastClickSlot") void fabricatedBackpacks$lastClickSlot(Slot slot);
    @Accessor("lastClickTime") void fabricatedBackpacks$lastClickTime(long time);
    @Accessor("doubleclick") void fabricatedBackpacks$doubleClick(boolean value);
}
