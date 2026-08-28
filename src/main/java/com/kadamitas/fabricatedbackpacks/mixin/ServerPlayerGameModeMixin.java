package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin implements UpgradeAccess.Mining {
    @Override @Accessor("isDestroyingBlock") public abstract boolean fabricatedBackpacks$isDestroyingBlock();
}
