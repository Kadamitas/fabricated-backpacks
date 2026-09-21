package com.kadamitas.fabricatedbackpacks.platform.network;

import com.kadamitas.fabricatedbackpacks.platform.NativeAttachmentType;

public final class NativeAttachmentClient {
    private NativeAttachmentClient() {}
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(AttachmentPayload.TYPE, (packet, context) -> {
            var level = context.client().level;
            var type = NativeAttachmentType.TYPES.get(packet.key());
            if (level == null || type == null) return;
            var entity = level.getEntity(packet.entityId());
            if (entity != null) type.acceptClient(entity, packet.data());
        });
    }
}
