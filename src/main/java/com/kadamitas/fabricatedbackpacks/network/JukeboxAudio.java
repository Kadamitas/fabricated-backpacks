package com.kadamitas.fabricatedbackpacks.network;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.JukeboxSong;

import java.util.Objects;
import java.util.Optional;

/** Named songs resolve in each level's registry; bootstrap holder identities never cross the wire. */
public record JukeboxAudio(String identity, Optional<ResourceLocation> song, int entityId,
                           BlockPos position, int remainingTicks) implements CustomPacketPayload {
    public static final int MAX_IDENTITY_LENGTH = 160;
    public static final int MAX_SONG_KEY_LENGTH = 256;
    /** A playback lease lasts at most 24 hours, even for malformed mod-provided song lengths. */
    public static final int MAX_REMAINING_TICKS = 20 * 60 * 60 * 24;
    public static final Type<JukeboxAudio> TYPE = new Type<>(BackpackRegistry.id("jukebox_audio"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxAudio> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_IDENTITY_LENGTH), JukeboxAudio::identity,
            ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_SONG_KEY_LENGTH)
                    .map(ResourceLocation::parse, ResourceLocation::toString)), JukeboxAudio::song,
            ByteBufCodecs.VAR_INT, JukeboxAudio::entityId, BlockPos.STREAM_CODEC, JukeboxAudio::position,
            ByteBufCodecs.VAR_INT, JukeboxAudio::remainingTicks, JukeboxAudio::new);
    public JukeboxAudio {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(song, "song");
        Objects.requireNonNull(position, "position");
        if (identity.isBlank() || identity.length() > MAX_IDENTITY_LENGTH
                || identity.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid audio identity");
        if (song.isPresent() && song.orElseThrow().toString().length() > MAX_SONG_KEY_LENGTH)
            throw new IllegalArgumentException("Song key is too long");
        if (entityId < -1 || !BlockPos.of(position.asLong()).equals(position))
            throw new IllegalArgumentException("Invalid audio source");
        if (remainingTicks < 0 || remainingTicks > MAX_REMAINING_TICKS
                || song.isPresent() != (remainingTicks > 0))
            throw new IllegalArgumentException("Invalid audio duration");
        if (song.isEmpty() && (entityId != -1 || !position.equals(BlockPos.ZERO)))
            throw new IllegalArgumentException("A stop packet has no source");
        position = position.immutable();
    }

    /** Unknown, unnamed, or invalid songs cannot create an unbounded direct-value packet. */
    public static Optional<JukeboxAudio> playback(RegistryAccess registries, String identity,
                                                  Holder<JukeboxSong> song, int entityId,
                                                  BlockPos position, int remainingTicks) {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(song, "song");
        return song.unwrapKey().filter(key -> key.isFor(Registries.JUKEBOX_SONG))
                .filter(key -> key.location().toString().length() <= MAX_SONG_KEY_LENGTH)
                .flatMap(key -> registries.lookup(Registries.JUKEBOX_SONG).flatMap(registry -> registry.get(key)))
                .filter(named -> validLength(named.value()))
                .flatMap(named -> {
                    int ticks = Math.min(remainingTicks, boundedLengthTicks(named.value()));
                    return ticks <= 0 ? Optional.empty() : Optional.of(new JukeboxAudio(
                            identity, Optional.of(named.key().location()), entityId, position, ticks));
                });
    }

    public static JukeboxAudio stop(String identity) {
        return new JukeboxAudio(identity, Optional.empty(), -1, BlockPos.ZERO, 0);
    }

    /** Missing keys after a registry change stop only this source; they never choose a fallback song. */
    public Optional<JukeboxSong> resolveSong(RegistryAccess registries) {
        return song.flatMap(id -> registries.lookup(Registries.JUKEBOX_SONG)
                .flatMap(registry -> registry.get(ResourceKey.create(Registries.JUKEBOX_SONG, id))))
                .map(Holder::value).filter(JukeboxAudio::validLength);
    }

    private static boolean validLength(JukeboxSong value) {
        return Float.isFinite(value.lengthInSeconds()) && value.lengthInSeconds() > 0;
    }

    /** Cap seconds before multiplication or narrowing, preserving vanilla float rounding for ordinary songs. */
    public static int boundedLengthTicks(JukeboxSong song) {
        if (!validLength(song)) return 0;
        float seconds = Math.min(song.lengthInSeconds(), MAX_REMAINING_TICKS / 20.0F);
        return (int)Math.ceil(seconds * 20.0F);
    }

    @Override public Type<JukeboxAudio> type() { return TYPE; }
}
