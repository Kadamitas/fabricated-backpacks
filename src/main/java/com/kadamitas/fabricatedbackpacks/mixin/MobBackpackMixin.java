package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.world.WorldBackpacks;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
abstract class MobBackpackMixin {
    @Inject(method = "finalizeSpawn", at = @At("RETURN"))
    private void fabricatedBackpacks$deferEquipment(ServerLevelAccessor level, DifficultyInstance difficulty,
                                                   EntitySpawnReason reason, SpawnGroupData group,
                                                   CallbackInfoReturnable<SpawnGroupData> callback) {
        WorldBackpacks.onFinalize((Mob)(Object)this, difficulty, reason);
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void fabricatedBackpacks$carrierTick(CallbackInfo callback) { WorldBackpacks.tick((Mob)(Object)this); }
}
