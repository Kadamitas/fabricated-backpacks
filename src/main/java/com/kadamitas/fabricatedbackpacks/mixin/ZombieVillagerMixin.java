package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.UUID;

@Mixin(ZombieVillager.class)
abstract class ZombieVillagerMixin implements UpgradeAccess.VillagerConversion {
    @Override @Invoker("startConverting") public abstract void fabricatedBackpacks$startConverting(UUID player, int ticks);
}
