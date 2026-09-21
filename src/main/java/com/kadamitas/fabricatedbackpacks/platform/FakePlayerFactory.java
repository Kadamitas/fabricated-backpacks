package com.kadamitas.fabricatedbackpacks.platform;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

public final class FakePlayerFactory {
    private static final Map<ServerLevel, Map<UUID, FakePlayer>> PLAYERS = new WeakHashMap<>();
    private FakePlayerFactory() {}
    public static FakePlayer get(ServerLevel level, GameProfile profile) { return PLAYERS.computeIfAbsent(level, ignored -> new HashMap<>()).computeIfAbsent(profile.id(), ignored -> new FakePlayer(level, profile)); }
}
