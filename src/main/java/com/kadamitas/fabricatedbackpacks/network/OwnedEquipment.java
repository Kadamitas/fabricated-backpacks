package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Private equipment is sent only to its owner, and can only update the receiving client's own slot. */
public record OwnedEquipment(ItemStack stack) implements CustomPacketPayload {
    public static final Type<OwnedEquipment> TYPE = new Type<>(BackpackRegistry.id("owned_equipment"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OwnedEquipment> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.map(OwnedEquipment::new, OwnedEquipment::stack);

    public OwnedEquipment { stack = stack.copy(); }

    @Override public Type<OwnedEquipment> type() { return TYPE; }
}
