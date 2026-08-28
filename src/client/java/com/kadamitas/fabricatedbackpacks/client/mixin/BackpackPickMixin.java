package com.kadamitas.fabricatedbackpacks.client.mixin;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.network.PickBackpackItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep stored contents authoritative for operator copies and survival bag lookups. */
@Mixin(Minecraft.class)
abstract class BackpackPickMixin {
    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void fabricatedBackpacks$requestPick(CallbackInfo callback) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.player == null || client.level == null
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !ClientPlayNetworking.canSend(PickBackpackItem.TYPE)) return;
        var state = client.level.getBlockState(hit.getBlockPos());
        if (state.isAir()) return;
        if (client.player.getAbilities().instabuild) {
            if (Screen.hasControlDown() && client.player.hasPermissions(2)
                    && client.level.getBlockEntity(hit.getBlockPos()) instanceof BackpackBlockEntity) {
                // Do not let vanilla upload a copy made from the intentionally sanitized client BE.
                ClientPlayNetworking.send(new PickBackpackItem(hit.getBlockPos(), true));
                callback.cancel();
            }
            return;
        }
        var item = state.getBlock().getCloneItemStack(client.level, hit.getBlockPos(), state);
        if (!item.isEmpty() && client.player.getInventory().findSlotMatchingItem(item) < 0)
            ClientPlayNetworking.send(new PickBackpackItem(hit.getBlockPos()));
    }
}
