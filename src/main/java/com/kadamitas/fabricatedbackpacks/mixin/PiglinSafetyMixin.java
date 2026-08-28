package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends vanilla's equipment predicate without changing anger, theft or attack behavior. */
@Mixin(PiglinAi.class)
public abstract class PiglinSafetyMixin {
    @Inject(method = "isWearingSafeArmor", at = @At("RETURN"), cancellable = true)
    private static void fabricatedBackpacks$nativeEquipment(LivingEntity entity, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue() || !(entity instanceof Player player)) return;
        ItemStack equipped = BackpackEquipment.get(player);
        if (BackpackRegistry.isBackpack(equipped) && equipped.is(ItemTags.PIGLIN_SAFE_ARMOR)) result.setReturnValue(true);
    }
}
