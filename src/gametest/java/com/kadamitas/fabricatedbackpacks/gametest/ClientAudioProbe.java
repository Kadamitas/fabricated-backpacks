package com.kadamitas.fabricatedbackpacks.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Observes actual SoundManager instances; a packet or a saved playing flag is insufficient evidence. */
final class ClientAudioProbe implements SoundEventListener, AutoCloseable {
    private final ClientGameTestContext context;
    private final List<SoundInstance> records = new ArrayList<>();

    ClientAudioProbe(ClientGameTestContext context) {
        this.context = context;
        context.runOnClient(client -> {
            client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
            client.options.getSoundSourceOptionInstance(SoundSource.RECORDS).set(1.0);
            client.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
            client.getSoundManager().addListener(this);
        });
    }

    @Override public void onPlaySound(SoundInstance sound, WeighedSoundEvents event, float range) {
        if (sound.getSource() == SoundSource.RECORDS) records.add(sound);
    }

    void awaitActive(int count) {
        context.waitFor(client -> records.stream().filter(client.getSoundManager()::isActive).count() == count, 200);
    }

    /** A remote server does not share this JVM's accelerated client-test tick clock. */
    void awaitActive(int count, Duration timeout) throws InterruptedException {
        awaitRemote(client -> records.stream().filter(client.getSoundManager()::isActive).count() == count,
                timeout, count + " active record instances");
    }

    void awaitPosition(SoundInstance sound, double x, Duration timeout) throws InterruptedException {
        awaitRemote(client -> client.getSoundManager().isActive(sound) && Math.abs(sound.getX() - x) < .1,
                timeout, "the same active record instance at x=" + x);
    }

    private void awaitRemote(Predicate<Minecraft> predicate, Duration timeout, String description) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Audio wait timeout must be positive");
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (context.computeOnClient(predicate::test)) return;
            context.waitTick();
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        if (!context.computeOnClient(predicate::test)) {
            throw new AssertionError("Timed out waiting for " + description + "; active records: "
                    + context.computeOnClient(client -> records.stream().filter(client.getSoundManager()::isActive)
                    .map(sound -> sound.getIdentifier().toString()).toList()));
        }
    }

    SoundInstance onlyActive(Identifier soundEvent) {
        return context.computeOnClient(client -> {
            List<SoundInstance> matching = records.stream().filter(client.getSoundManager()::isActive)
                    .filter(sound -> sound.getIdentifier().equals(soundEvent)).toList();
            if (matching.size() != 1) throw new AssertionError("Expected one active " + soundEvent + " instance, got " + matching.size());
            return matching.getFirst();
        });
    }

    void requireIsolatedStop(SoundInstance stopped, SoundInstance survivor) {
        context.runOnClient(client -> {
            if (stopped == survivor || client.getSoundManager().isActive(stopped) || !client.getSoundManager().isActive(survivor)) {
                throw new AssertionError("Stopping " + stopped.getIdentifier() + " must leave the same "
                        + survivor.getIdentifier() + " instance active");
            }
        });
    }

    SoundInstance first() { return context.computeOnClient(client -> records.getFirst()); }

    String channels() { return context.computeOnClient(client -> client.getSoundManager().getChannelDebugString()); }

    @Override public void close() { context.runOnClient(client -> client.getSoundManager().removeListener(this)); }
}
