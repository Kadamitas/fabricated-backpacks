package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin implements UpgradeAccess.ItemClaims {
    @Override @Accessor("target") public abstract UUID fabricatedBackpacks$target();
    @Inject(method = "tick", at = @At("HEAD"))
    private void fabricatedBackpacks$protect(CallbackInfo callback) { BackpackRuntime.protectDropped((ItemEntity) (Object) this); }
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void fabricatedBackpacks$resistDamage(ServerLevel level, DamageSource damage, float amount, CallbackInfoReturnable<Boolean> callback) {
        if (BackpackRuntime.everlasting(((ItemEntity) (Object) this).getItem())) callback.setReturnValue(false);
    }
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void fabricatedBackpacks$pickup(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            ItemEntity item = (ItemEntity) (Object) this;
            BackpackRuntime.pickup(item, serverPlayer);
            if (item.isRemoved() || item.getItem().isEmpty()) callback.cancel();
        }
    }
}
