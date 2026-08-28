package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.network.JukeboxAudio;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.mixin.networking.accessor.ServerChunkLoadingManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.bag;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.upgrade;

/** Real server tracking and outbound packets; the embedded recipient does not exercise client audio. */
public final class AudioTrackingGameTests {
    private AudioTrackingGameTests() {}

    public static void rapidRetracking(GameTestHelper helper) {
        TrackingAudit audit = new TrackingAudit(helper);
        helper.onEachTick(audit::tick);
    }

    private static final class TrackingAudit {
        final GameTestHelper helper;
        final ServerLevel level;
        final PacketFixture listener;
        final Source affected;
        final Source unrelated;
        int elapsed;
        boolean playing;
        boolean closed;
        long start;
        long finish;
        long unrelatedFinish;

        TrackingAudit(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            listener = new PacketFixture(helper);
            affected = source(helper, new BlockPos(2, 1, 2));
            unrelated = source(helper, new BlockPos(5, 1, 2));
        }

        void tick() {
            if (closed) return;
            try {
                listener.pump();
                // Use the actual chunk tracker and acknowledge only batches the fixture received.
                level.getChunkSource().move(listener.player);
                affected.tick(level);
                unrelated.tick(level);
                if (++elapsed >= 180) {
                    helper.assertTrue(false, "Connected audio fixture did not finish tracking lifecycle within 180 ticks; "
                            + "registered=" + ServerPlayNetworking.canSend(listener.player, JukeboxAudio.TYPE)
                            + ", firstTracked=" + tracked(affected) + ", secondTracked=" + tracked(unrelated));
                }
                long now = level.getGameTime();
                if (!playing) {
                    if (now % 20 != 3 || !tracked(affected) || !tracked(unrelated)
                            || !ServerPlayNetworking.canSend(listener.player, JukeboxAudio.TYPE)) return;
                    affected.play(level);
                    unrelated.play(level);
                    listener.pump();
                    helper.assertValueEqual(listener.audio(affected.identity(), true).size(), 1, "Initial tracked source sends one playback packet");
                    helper.assertValueEqual(listener.audio(unrelated.identity(), true).size(), 1, "Independent tracked source sends its own playback packet");
                    start = NbtAccess.getLongOr(affected.bag.settings(affected.upgrade), "song_started", -1);
                    finish = NbtAccess.getLongOr(affected.bag.settings(affected.upgrade), "song_finish", -1);
                    unrelatedFinish = NbtAccess.getLongOr(unrelated.bag.settings(unrelated.upgrade), "song_finish", -1);
                    helper.assertValueEqual(start, now, "Initial playback starts at the server game time");
                    helper.assertTrue(finish > start + 20, "The actual registry song has a nonempty playback interval");
                    playing = true;
                } else if (now == start + 5) {
                    exerciseRetracking(now);
                    close();
                    helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private boolean tracked(Source source) {
            return PlayerLookup.tracking(source.carrier).contains(listener.player);
        }

        private void exerciseRetracking(long now) {
            helper.assertTrue(now % 20 != 0, "The regression runs before the periodic listener reconciliation boundary");
            helper.assertTrue(tracked(affected) && tracked(unrelated), "Both carriers belong to the actual tracking set before removal");
            listener.clear();

            // This invokes vanilla ServerEntity.removePairing/addPairing through ChunkMap,
            // without discarding the live carrier or substituting a SoundBridge.
            level.getChunkSource().removeEntity(affected.carrier);
            helper.assertFalse(((ServerChunkLoadingManagerAccessor) level.getChunkSource().chunkMap)
                    .getEntityTrackers().containsKey(affected.carrier.getId()), "The affected carrier really leaves the chunk tracker");
            helper.assertTrue(level.getEntity(affected.carrier.getId()) == affected.carrier, "Tracking removal does not remove the live carrier from the level");
            level.getChunkSource().addEntity(affected.carrier);
            listener.pump();
            helper.assertTrue(tracked(affected) && tracked(unrelated), "The same recipient is paired again while the other source remains tracked");
            helper.assertValueEqual(level.getGameTime(), now, "Removal and pairing occur in the same server tick");
            helper.assertValueEqual(listener.audio(affected.identity(), false).size(), 1, "Tracking stop immediately sends one scoped leave packet");
            helper.assertValueEqual(listener.audio(affected.identity(), true).size(), 0, "Tracking start defers playback until a bag tick after pairing");
            helper.assertValueEqual(listener.audio(unrelated.identity()).size(), 0, "Changing one carrier sends no packets for the independent source");

            affected.tick(level);
            unrelated.tick(level);
            listener.pump();
            List<JukeboxAudio> replay = listener.audio(affected.identity(), true);
            helper.assertValueEqual(replay.size(), 1, "A dirty listener is replayed exactly once before the periodic poll");
            JukeboxAudio packet = replay.getFirst();
            helper.assertValueEqual(packet.entityId(), affected.carrier.getId(), "Replay remains attached to the original carrier");
            helper.assertValueEqual(packet.remainingTicks(), Math.toIntExact(finish - now - 20), "Replay sends only the unexpired song duration, excluding the completion padding");
            helper.assertTrue(packet.remainingTicks() > 0 && packet.remainingTicks() <= JukeboxAudio.MAX_REMAINING_TICKS,
                    "Replay duration satisfies the bounded audio protocol");
            int pairing = listener.pairingIndex(affected.carrier.getId());
            int playback = listener.playbackIndex(affected.identity());
            helper.assertTrue(pairing >= 0 && playback > pairing, "The real carrier pairing packet precedes the replay packet");
            assertDeadlines();

            affected.tick(level);
            unrelated.tick(level);
            listener.pump();
            helper.assertValueEqual(listener.audio(affected.identity(), true).size(), 1, "A second stable bag tick does not replay a delivered listener");
            helper.assertValueEqual(listener.audio(unrelated.identity()).size(), 0, "Unrelated playback is neither stopped nor restarted by reconciliation");

            helper.assertTrue(JukeboxRuntime.action(affected.bag, affected.upgrade, level,
                    affected.carrier.blockPosition(), affected.carrier, "stop"), "Explicit stop changes the active playlist");
            listener.pump();
            helper.assertValueEqual(listener.audio(affected.identity(), false).size(), 2, "Explicit stop sends its own scoped stop packet");
            listener.clear();
            level.getChunkSource().removeEntity(affected.carrier);
            level.getChunkSource().addEntity(affected.carrier);
            affected.tick(level);
            unrelated.tick(level);
            listener.pump();
            helper.assertTrue(tracked(affected), "The stopped carrier is actually paired again");
            helper.assertValueEqual(listener.audio(affected.identity()).size(), 0, "Tracking a stopped playlist never resurrects its audio");
            helper.assertTrue(!NbtAccess.getBooleanOr(affected.bag.settings(affected.upgrade), "playing", true), "Stopped playlist state remains stopped");
            helper.assertTrue(NbtAccess.getBooleanOr(unrelated.bag.settings(unrelated.upgrade), "playing", false), "The independent playlist remains playing");
            helper.assertValueEqual(NbtAccess.getLongOr(unrelated.bag.settings(unrelated.upgrade), "song_finish", -1), unrelatedFinish,
                    "Stopping and retracking another carrier preserves the independent finish time");
            helper.assertValueEqual(listener.audio(unrelated.identity()).size(), 0, "Explicitly stopped source tracking does not replay the independent source");
        }

        private void assertDeadlines() {
            helper.assertValueEqual(NbtAccess.getLongOr(affected.bag.settings(affected.upgrade), "song_started", -1), start,
                    "Listener churn does not restart the song clock");
            helper.assertValueEqual(NbtAccess.getLongOr(affected.bag.settings(affected.upgrade), "song_finish", -1), finish,
                    "Listener churn preserves the original finish deadline");
        }

        private void close() {
            if (closed) return;
            closed = true;
            JukeboxRuntime.stopUpgrade(affected.bag, affected.upgrade.slot(), level.getServer());
            JukeboxRuntime.stopUpgrade(unrelated.bag, unrelated.upgrade.slot(), level.getServer());
            listener.close();
        }
    }

    private static Source source(GameTestHelper helper, BlockPos position) {
        Mob carrier = helper.spawn(EntityType.PIG, position);
        carrier.setNoAi(true);
        carrier.setNoGravity(true);
        carrier.setInvulnerable(true);
        BagInventory inventory = bag(BackpackTier.NETHERITE, UpgradeKind.JUKEBOX);
        InstalledUpgrade upgrade = upgrade(inventory, 0);
        inventory.upgradeInventory(upgrade).setItem(0, new ItemStack(Items.MUSIC_DISC_BLOCKS));
        return new Source(carrier, inventory, upgrade);
    }

    private record Source(Mob carrier, BagInventory bag, InstalledUpgrade upgrade) {
        void tick(ServerLevel level) { JukeboxRuntime.tick(bag, upgrade, level, carrier.blockPosition(), carrier); }
        void play(ServerLevel level) { JukeboxRuntime.action(bag, upgrade, level, carrier.blockPosition(), carrier, "play"); }
        String identity() { return bag.identity() + ":" + upgrade.slot() + ":" + NbtAccess.getStringOr(bag.settings(upgrade), "jukebox_identity", ""); }
    }

    private static final class PacketFixture implements AutoCloseable {
        final ServerPlayer player;
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        final EmbeddedChannel channel = new EmbeddedChannel(connection);
        final List<Packet<?>> packets = new ArrayList<>();
        int pendingAcknowledgments;
        final List<Integer> pendingTeleports = new ArrayList<>();

        PacketFixture(GameTestHelper helper) {
            UUID id = UUID.randomUUID();
            var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "fb_audio_" + id.toString().substring(0, 6)), false);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof Packet<?> packet) capture(packet);
                    super.write(context, message, promise);
                }
            });
            helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            pump(); // Acknowledge only the initial native teleport actually observed by this recipient.
            player.setPos(helper.absoluteVec(new Vec3(3.5, 1, 5.5)));
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
                    new RegistrationPayload(RegistrationPayload.REGISTER, List.of(JukeboxAudio.TYPE.id()))));
        }

        private void capture(Packet<?> packet) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Packet<?> child : bundle.subPackets()) capture(child);
            } else {
                packets.add(packet);
                if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingAcknowledgments++;
                if (packet instanceof ClientboundPlayerPositionPacket teleport) pendingTeleports.add(teleport.getId());
            }
        }

        void pump() {
            channel.runPendingTasks();
            for (int id : List.copyOf(pendingTeleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            pendingTeleports.clear();
            while (pendingAcknowledgments > 0) {
                pendingAcknowledgments--;
                player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64));
            }
            channel.runPendingTasks();
        }

        void clear() { pump(); packets.clear(); }

        List<JukeboxAudio> audio(String identity) {
            return packets.stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                    .map(ClientboundCustomPayloadPacket.class::cast).map(ClientboundCustomPayloadPacket::payload)
                    .filter(JukeboxAudio.class::isInstance).map(JukeboxAudio.class::cast)
                    .filter(packet -> packet.identity().equals(identity)).toList();
        }

        List<JukeboxAudio> audio(String identity, boolean playing) {
            return audio(identity).stream().filter(packet -> packet.song().isPresent() == playing).toList();
        }

        int pairingIndex(int entityId) {
            for (int index = 0; index < packets.size(); index++) {
                if (packets.get(index) instanceof ClientboundAddEntityPacket packet && packet.getId() == entityId) return index;
            }
            return -1;
        }

        int playbackIndex(String identity) {
            for (int index = 0; index < packets.size(); index++) {
                if (packets.get(index) instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload() instanceof JukeboxAudio audio
                        && audio.identity().equals(identity) && audio.song().isPresent()) return index;
            }
            return -1;
        }

        @Override public void close() {
            player.closeContainer();
            connection.disconnect(Component.literal("Audio tracking fixture finished"));
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
        }
    }
}
