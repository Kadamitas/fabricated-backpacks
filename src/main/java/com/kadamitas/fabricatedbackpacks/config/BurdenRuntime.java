package com.kadamitas.fabricatedbackpacks.config;

import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/** Optional weight effect for directly carried/equipped bags, independent of upgrade ticking policy. */
public final class BurdenRuntime {
    private BurdenRuntime() { }

    public static long backpackCount(ServerPlayer player) {
        long count = count(BackpackEquipment.get(player));
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) count += count(player.getInventory().getItem(slot));
        return count;
    }
    private static int count(ItemStack stack) { return !stack.isEmpty() && BackpackRegistry.isBackpack(stack) ? stack.getCount() : 0; }

    public static void tick(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.tickCount % 20 != 0) return;
        var burden = BackpackConfig.get().storage().burden();
        if (!burden.enabled()) return;
        long extra = Math.max(0, backpackCount(player) - burden.freeBackpacks());
        if (extra == 0) return;
        int level = (int)Math.min(10, extra * burden.levelsPerExtra());
        var effect = player.registryAccess().lookupOrThrow(Registries.MOB_EFFECT)
                .get(ResourceKey.create(Registries.MOB_EFFECT, ResourceLocation.parse(burden.effect())));
        // Use a short ordinary effect. Never strip somebody else's potion when their bag count falls.
        effect.ifPresent(holder -> player.addEffect(new MobEffectInstance(holder, 40, level - 1, true, false)));
    }
}
