package com.kadamitas.fabricatedbackpacks.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Client layout only; slot indices and all server inventory rules remain unchanged. */
@Mixin(Slot.class)
public interface SlotPositionAccess {
    @Mutable @Accessor("x") void fabricatedBackpacks$x(int x);
    @Mutable @Accessor("y") void fabricatedBackpacks$y(int y);
}
