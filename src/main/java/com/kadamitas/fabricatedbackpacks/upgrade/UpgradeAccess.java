package com.kadamitas.fabricatedbackpacks.upgrade;

import java.util.UUID;

/** Narrow vanilla access contracts implemented by the mod's registered accessors/invokers. */
public final class UpgradeAccess {
    private UpgradeAccess() { }
    public interface ItemClaims { UUID fabricatedBackpacks$target(); }
    public interface Mining { boolean fabricatedBackpacks$isDestroyingBlock(); }
    public interface VillagerConversion { void fabricatedBackpacks$startConverting(UUID player, int ticks); }
    public interface LastPlayerDamage {
        int fabricatedBackpacks$lastPlayerDamageTicks();
        net.minecraft.world.entity.player.Player fabricatedBackpacks$lastPlayerDamager();
    }
}
