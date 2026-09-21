package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class NativePickBlockMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handlePickItemFromBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private void fabricatedBackpacks$pick(ServerboundPickItemFromBlockPacket packet, CallbackInfo callback) {
        BackpackRuntime.pickBlock(player, packet.pos());
    }
}
