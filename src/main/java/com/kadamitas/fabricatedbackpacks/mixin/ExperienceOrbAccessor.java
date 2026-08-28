package com.kadamitas.fabricatedbackpacks.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccessor {
    @Accessor("count") int fabricatedBackpacks$getCount();
    @Accessor("count") void fabricatedBackpacks$setCount(int count);
    @Accessor("value") void fabricatedBackpacks$setValue(int value);
}
