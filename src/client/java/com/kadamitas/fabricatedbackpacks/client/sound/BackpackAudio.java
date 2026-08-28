package com.kadamitas.fabricatedbackpacks.client.sound;

import com.kadamitas.fabricatedbackpacks.network.JukeboxAudio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.JukeboxSong;

import java.util.HashMap;
import java.util.Map;

/** Audio instances are keyed by backpack, so stopping one never silences a second identical record. */
public final class BackpackAudio {
    private static final Map<String, MovingRecord> PLAYING = new HashMap<>();
    private BackpackAudio() {}
    public static void receive(JukeboxAudio packet) {
        Minecraft client = Minecraft.getInstance();
        MovingRecord previous = PLAYING.remove(packet.identity());
        if (previous != null) client.getSoundManager().stop(previous);
        if (client.level != null) packet.resolveSong(client.level.registryAccess()).ifPresent(song -> {
            var sound = new MovingRecord(packet, client.level, song);
            PLAYING.put(packet.identity(), sound);
            client.getSoundManager().play(sound);
        });
    }
    public static void tick(Minecraft client) {
        if (client.level == null) {
            PLAYING.values().forEach(client.getSoundManager()::stop);
            PLAYING.clear();
        } else PLAYING.values().removeIf(MovingRecord::isStopped);
    }
    private static final class MovingRecord extends AbstractTickableSoundInstance {
        private final ClientLevel level;
        private final int entityId;
        private int remaining;
        MovingRecord(JukeboxAudio packet, ClientLevel level, JukeboxSong song) {
            super(song.soundEvent().value(), SoundSource.RECORDS, RandomSource.create());
            this.level = level;
            entityId = packet.entityId();
            remaining = Math.min(packet.remainingTicks(), JukeboxAudio.boundedLengthTicks(song));
            x = packet.position().getX() + 0.5;
            y = packet.position().getY() + 0.5;
            z = packet.position().getZ() + 0.5;
            volume = 4;
            pitch = 1;
            looping = false;
            attenuation = Attenuation.LINEAR;
        }
        @Override public void tick() {
            if (Minecraft.getInstance().level != level || --remaining < 0) { stop(); return; }
            if (entityId >= 0) {
                Entity carrier = level.getEntity(entityId);
                if (carrier == null || carrier.isRemoved()) { stop(); return; }
                x = carrier.getX(); y = carrier.getY() + carrier.getBbHeight() / 2; z = carrier.getZ();
            }
        }
    }
}
