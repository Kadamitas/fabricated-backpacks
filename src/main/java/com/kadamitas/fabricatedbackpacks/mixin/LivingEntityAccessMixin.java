package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserves vanilla's timed player-kill attribution, including a tamed wolf's owner. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessMixin extends UpgradeAccess.LastPlayerDamage {
    @Override @Accessor("lastHurtByPlayerTime") int fabricatedBackpacks$lastPlayerDamageTicks();
    @Override @Accessor("lastHurtByPlayer") Player fabricatedBackpacks$lastPlayerDamager();
}
