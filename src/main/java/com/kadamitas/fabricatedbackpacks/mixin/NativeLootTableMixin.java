package com.kadamitas.fabricatedbackpacks.mixin;

import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import com.kadamitas.fabricatedbackpacks.world.ChestLoot;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observation only: NeoForge's LootTableLoadEvent performs the actual injection but cannot tell a
 * built-in table from a datapack replacement, which must stay untouched. Record each table's
 * resource right before the event fires for it.
 */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
abstract class NativeLootTableMixin {
    @Inject(method = "loadFromResource", at = @At("HEAD"))
    private static <T> void fabricatedBackpacks$observeSource(Decoder<T> decoder, RegistryOps<JsonElement> ops,
            ResourceKey<T> key, Resource resource, CallbackInfoReturnable<Either<T, Exception>> callback) {
        ChestLoot.observeSource(key, resource);
    }
}
