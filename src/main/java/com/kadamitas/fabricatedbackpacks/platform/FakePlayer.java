package com.kadamitas.fabricatedbackpacks.platform;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Non-joined automation actor. Forge66 removed the older common.util helper. */
public final class FakePlayer extends ServerPlayer {
    FakePlayer(ServerLevel level, GameProfile profile) { super(level.getServer(), level, profile, ClientInformation.createDefault()); }
}
