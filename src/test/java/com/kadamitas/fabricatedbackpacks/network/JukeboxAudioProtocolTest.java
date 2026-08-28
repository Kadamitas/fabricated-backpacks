package com.kadamitas.fabricatedbackpacks.network;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.ResourceLocationException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.JukeboxSong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JukeboxAudioProtocolTest {
    private static final String IDENTITY = "c8a960dc-bef7-45e9-af5b-e5bad787b3ba:0:15f71fd3-7229-44f5-aebe-9d7771350871";
    private static final ResourceLocation BLOCKS = ResourceLocation.withDefaultNamespace("blocks");
    private static final ResourceLocation CUSTOM = ResourceLocation.parse("music_pack:records/wind");

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void foreignRegisteredHolderFailsOldRegistryIdEncodingButNewPacketCrossesActualBytes() {
        // Separate frozen registries reproduce bootstrap-versus-level ownership without requiring a live item-component load.
        RegistryAccess defaults = registry(BLOCKS, song(345));
        Holder<JukeboxSong> itemSong = defaults.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, BLOCKS)).orElseThrow();
        RegistryAccess level = registry(BLOCKS, song(90));
        RegistryFriendlyByteBuf encoded = buffer(level);
        try {
            assertNotSame(itemSong, level.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, BLOCKS)).orElseThrow());
            IllegalArgumentException previousFailure = assertThrows(IllegalArgumentException.class,
                    () -> JukeboxSong.STREAM_CODEC.encode(encoded, itemSong));
            assertTrue(previousFailure.getMessage().contains("Can't find id"), "Reproduce the real TCP encoder failure");
            encoded.clear();
            JukeboxAudio packet = JukeboxAudio.playback(level, IDENTITY, itemSong, 42, new BlockPos(3, 80, -4), 2000).orElseThrow();
            assertEquals(1800, packet.remainingTicks(), "Use the level's song length, not the bootstrap default");
            JukeboxAudio.STREAM_CODEC.encode(encoded, packet);
            assertTrue(encoded.readableBytes() > 0);
            RegistryFriendlyByteBuf received = new RegistryFriendlyByteBuf(encoded.copy(), RegistryAccess.EMPTY);
            try {
                JukeboxAudio decoded = JukeboxAudio.STREAM_CODEC.decode(received);
                assertEquals(packet, decoded);
                assertEquals(0, received.readableBytes());
                assertEquals(Optional.of(BLOCKS), decoded.song());
                assertSame(level.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, BLOCKS)).orElseThrow().value(), decoded.resolveSong(level).orElseThrow());
            } finally { received.release(); }
        } finally { encoded.release(); }
    }

    @Test void namedDatapackSongsResolveInTheReceivingRegistryWithoutNumericIdsOrInlineValues() {
        RegistryAccess source = registry(CUSTOM, song(10));
        RegistryAccess server = registry(CUSTOM, song(30));
        JukeboxSong clientSong = song(20);
        RegistryAccess client = registry(CUSTOM, clientSong);
        Holder<JukeboxSong> foreign = source.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, CUSTOM)).orElseThrow();
        JukeboxAudio packet = JukeboxAudio.playback(server, IDENTITY, foreign, -1, new BlockPos(-6, 90, 7), 800).orElseThrow();
        JukeboxAudio decoded = roundTrip(packet);
        assertEquals(Optional.of(CUSTOM), decoded.song());
        assertEquals(600, decoded.remainingTicks());
        assertSame(clientSong, decoded.resolveSong(client).orElseThrow());
        assertTrue(decoded.resolveSong(registry(BLOCKS, song(10))).isEmpty(), "No default song for an absent datapack key");
        assertTrue(decoded.resolveSong(RegistryAccess.EMPTY).isEmpty());
    }

    @Test void directMissingAndInvalidRegistrySongsCannotProducePlayback() {
        RegistryAccess level = registry(CUSTOM, song(30));
        Holder<JukeboxSong> named = level.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, CUSTOM)).orElseThrow();
        assertTrue(JukeboxAudio.playback(level, IDENTITY, Holder.direct(song(30)), 1, BlockPos.ZERO, 20).isEmpty());
        assertTrue(JukeboxAudio.playback(RegistryAccess.EMPTY, IDENTITY, named, 1, BlockPos.ZERO, 20).isEmpty());
        assertTrue(JukeboxAudio.playback(registry(BLOCKS, song(30)), IDENTITY, named, 1, BlockPos.ZERO, 20).isEmpty());
        for (float seconds : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 0, -1}) {
            RegistryAccess invalid = registry(CUSTOM, song(seconds));
            assertTrue(JukeboxAudio.playback(invalid, IDENTITY, named, 1, BlockPos.ZERO, 20).isEmpty(), "seconds=" + seconds);
            assertTrue(new JukeboxAudio(IDENTITY, Optional.of(CUSTOM), 1, BlockPos.ZERO, 20).resolveSong(invalid).isEmpty());
        }
    }

    @Test void playbackBoundsItsLeaseAndNeverResurrectsAnExpiredSong() {
        RegistryAccess level = registry(CUSTOM, song(Float.MAX_VALUE));
        Holder<JukeboxSong> named = level.lookupOrThrow(Registries.JUKEBOX_SONG).get(ResourceKey.create(Registries.JUKEBOX_SONG, CUSTOM)).orElseThrow();
        JukeboxAudio maximum = JukeboxAudio.playback(level, IDENTITY, named, 1, BlockPos.ZERO, Integer.MAX_VALUE).orElseThrow();
        assertEquals(JukeboxAudio.MAX_REMAINING_TICKS, maximum.remainingTicks());
        assertEquals(maximum, roundTrip(maximum));
        assertEquals(1, JukeboxAudio.boundedLengthTicks(song(Float.MIN_VALUE)));
        assertEquals(1, JukeboxAudio.boundedLengthTicks(song(.05F)));
        assertEquals(21, JukeboxAudio.boundedLengthTicks(song(1.01F)));
        assertEquals(1, JukeboxAudio.playback(level, IDENTITY, named, 1, BlockPos.ZERO, 1).orElseThrow().remainingTicks());
        assertTrue(JukeboxAudio.playback(level, IDENTITY, named, 1, BlockPos.ZERO, 0).isEmpty());
        assertTrue(JukeboxAudio.playback(level, IDENTITY, named, 1, BlockPos.ZERO, -1).isEmpty());
    }

    @Test void stopIsCanonicalAndNeedsNoRegistry() {
        JukeboxAudio stop = JukeboxAudio.stop(IDENTITY);
        assertEquals(stop, roundTrip(stop));
        assertTrue(stop.resolveSong(RegistryAccess.EMPTY).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.empty(), 1, BlockPos.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.empty(), -1, new BlockPos(1, 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.empty(), -1, BlockPos.ZERO, 1));
    }

    @Test void textBoundsAndMandatoryFieldsApplyBeforeEncoding() {
        ResourceLocation longestKey = ResourceLocation.parse("x:" + "a".repeat(JukeboxAudio.MAX_SONG_KEY_LENGTH - 2));
        JukeboxAudio maximum = new JukeboxAudio("a".repeat(JukeboxAudio.MAX_IDENTITY_LENGTH),
                Optional.of(longestKey), -1, BlockPos.ZERO, 1);
        assertEquals(maximum, roundTrip(maximum));
        for (String invalid : List.of("", " ", "line\nbreak", "x".repeat(JukeboxAudio.MAX_IDENTITY_LENGTH + 1)))
            assertThrows(IllegalArgumentException.class, () -> JukeboxAudio.stop(invalid));
        ResourceLocation tooLong = ResourceLocation.parse("x:" + "a".repeat(JukeboxAudio.MAX_SONG_KEY_LENGTH - 1));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.of(tooLong), 1, BlockPos.ZERO, 1));
        assertThrows(NullPointerException.class, () -> JukeboxAudio.stop(null));
        assertThrows(NullPointerException.class, () -> new JukeboxAudio(IDENTITY, null, -1, BlockPos.ZERO, 0));
        assertThrows(NullPointerException.class, () -> new JukeboxAudio(IDENTITY, Optional.empty(), -1, null, 0));
    }

    @Test void sourcePositionIsImmutableAndMustFitItsWireRepresentation() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(3, -64, 5);
        JukeboxAudio packet = new JukeboxAudio(IDENTITY, Optional.of(CUSTOM), Integer.MAX_VALUE, mutable, 1);
        mutable.set(9, 90, 9);
        assertEquals(new BlockPos(3, -64, 5), packet.position());
        assertEquals(packet, roundTrip(packet));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.of(CUSTOM), -2, BlockPos.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.of(CUSTOM), -1, new BlockPos(1 << 25, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> new JukeboxAudio(IDENTITY, Optional.of(CUSTOM), -1, new BlockPos(0, 1 << 11, 0), 1));
    }

    @Test void decoderRejectsOversizedStringsBeforeAllocatingPayloads() {
        RegistryFriendlyByteBuf buffer = buffer(RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(DecoderException.class, () -> JukeboxAudio.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUtf(IDENTITY);
            buffer.writeBoolean(true);
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(DecoderException.class, () -> JukeboxAudio.STREAM_CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUtf(IDENTITY);
            buffer.writeBoolean(true);
            buffer.writeUtf("x:" + "a".repeat(JukeboxAudio.MAX_SONG_KEY_LENGTH - 1));
            assertThrows(DecoderException.class, () -> JukeboxAudio.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void decoderRejectsMalformedKeysAndInvalidDurations() {
        RegistryFriendlyByteBuf buffer = buffer(RegistryAccess.EMPTY);
        try {
            buffer.writeUtf(IDENTITY);
            buffer.writeBoolean(true);
            buffer.writeUtf("Bad Namespace:record");
            assertThrows(ResourceLocationException.class, () -> JukeboxAudio.STREAM_CODEC.decode(buffer));
            for (int ticks : new int[] {-1, 0, JukeboxAudio.MAX_REMAINING_TICKS + 1, Integer.MAX_VALUE}) {
                buffer.clear();
                buffer.writeUtf(IDENTITY);
                buffer.writeBoolean(true);
                buffer.writeUtf(CUSTOM.toString());
                buffer.writeVarInt(1);
                BlockPos.STREAM_CODEC.encode(buffer, BlockPos.ZERO);
                buffer.writeVarInt(ticks);
                assertThrows(IllegalArgumentException.class, () -> JukeboxAudio.STREAM_CODEC.decode(buffer), "ticks=" + ticks);
            }
        } finally { buffer.release(); }
    }

    private static JukeboxSong song(float seconds) {
        return new JukeboxSong(Holder.direct(SoundEvent.createVariableRangeEvent(ResourceLocation.parse("music_pack:record.wind"))),
                Component.literal("Test record"), seconds, 1);
    }

    private static RegistryAccess registry(ResourceLocation key, JukeboxSong value) {
        MappedRegistry<JukeboxSong> registry = new MappedRegistry<>(Registries.JUKEBOX_SONG, Lifecycle.stable());
        Registry.registerForHolder(registry, key, value);
        registry.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
    }

    private static RegistryFriendlyByteBuf buffer(RegistryAccess access) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
    }

    private static JukeboxAudio roundTrip(JukeboxAudio packet) {
        RegistryFriendlyByteBuf buffer = buffer(RegistryAccess.EMPTY);
        try {
            JukeboxAudio.STREAM_CODEC.encode(buffer, packet);
            JukeboxAudio decoded = JukeboxAudio.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally { buffer.release(); }
    }
}
