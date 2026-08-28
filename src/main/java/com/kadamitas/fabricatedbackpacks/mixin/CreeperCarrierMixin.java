package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.world.WorldBackpacks;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Creeper.class)
abstract class CreeperCarrierMixin {
    @Inject(method = "spawnLingeringCloud", at = @At("HEAD"))
    private void fabricatedBackpacks$removeCarrierBuffs(CallbackInfo callback) { WorldBackpacks.beforeCreeperCloud((Creeper)(Object)this); }
}
