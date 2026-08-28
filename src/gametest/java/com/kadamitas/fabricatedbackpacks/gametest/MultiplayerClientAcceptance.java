package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import io.netty.channel.ChannelFuture;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.check;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickButton;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickPlayerSlot;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickSlot;

/** Two distinct Minecraft JVMs and a real TCP server; files coordinate actions but never simulate game packets. */
public final class MultiplayerClientAcceptance {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BlockPos SECOND_SOURCE = new BlockPos(0, 80, 6);
    private static final Duration REMOTE_AUDIO_TIMEOUT = Duration.ofSeconds(30);
    private static final Identifier WORN_TRACK = Identifier.withDefaultNamespace("music_disc.blocks");
    private static final Identifier PLACED_TRACK = Identifier.withDefaultNamespace("music_disc.13");
    private MultiplayerClientAcceptance() { }

    public static void host(ClientGameTestContext context) {
        Session files = new Session("host");
        try {
            files.create();
            Path world = files.root.resolve("server-world").toAbsolutePath().normalize();
            check(world.startsWith(files.root) && !world.equals(files.root) && !Files.exists(world, LinkOption.NOFOLLOW_LINKS),
                    "Dedicated test world must be a fresh child of this run's evidence directory");
            Properties properties = new Properties();
            properties.setProperty("level-name", world.toString());
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("server-port", "0");
            properties.setProperty("online-mode", "false");
            properties.setProperty("enforce-secure-profile", "false");
            properties.setProperty("view-distance", "3");
            properties.setProperty("simulation-distance", "3");
            properties.setProperty("max-players", "2");
            properties.setProperty("allow-flight", "true");
            try (var server = context.worldBuilder().createServer(properties)) {
                int port = server.computeOnServer(MultiplayerClientAcceptance::boundPort);
                server.runOnServer(value -> value.setPort(port));
                try (var connection = server.connect(); var audio = new ClientAudioProbe(context)) {
                    connection.waitForChunksRender();
                    UUID hostId = server.computeOnServer(value -> connection.getServerPlayer().getUUID());
                    server.runOnServer(value -> setupHost(value, hostId));
                    connection.waitForClientboundPackets();
                    verifyTcp(context);
                    context.getInput().pressKey(GLFW.GLFW_KEY_B);
                    context.waitForScreen(BackpackScreen.class);
                    clickButton(context, "1");
                    context.waitFor(client -> ((BackpackScreen)client.gui.screen()).getMenu().selectedSlot() == 0);
                    context.waitFor(client -> client.gui.screen().children().stream().anyMatch(widget -> widget instanceof AbstractWidget button && button.getMessage().getString().equals("Play")));
                    clickButton(context, "Play");
                    server.waitFor(value -> playing(worn(value, hostId)));
                    audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
                    files.screenshot(context, "host-playing-before-guest");
                    JsonObject ready = new JsonObject();
                    ready.addProperty("port", port);
                    ready.addProperty("host_uuid", hostId.toString());
                    String hostName = server.computeOnServer(value -> connection.getServerPlayer().getGameProfile().name());
                    ready.addProperty("host_name", hostName);
                    ready.addProperty("channels", audio.channels());
                    files.write("ready", ready);
                    hostActions(context, server, hostId, files, audio);
                }
            }
        } catch (Throwable failure) { files.failure(failure); throw new AssertionError("Real multiplayer host acceptance failed", failure); }
    }

    private static void hostActions(ClientGameTestContext context, TestDedicatedServerContext server, UUID hostId, Session files, ClientAudioProbe audio) throws Exception {
        JsonObject guest = files.await(context, "guest-connected", Duration.ofMinutes(3));
        UUID guestId = UUID.fromString(guest.get("guest_uuid").getAsString());
        long guestPid = guest.get("pid").getAsLong();
        check(guestPid != ProcessHandle.current().pid() && !guestId.equals(hostId), "Guest must be a second JVM and a distinct Minecraft profile");
        server.waitFor(value -> value.getPlayerList().getPlayer(guestId) != null, 2400);
        server.runOnServer(value -> {
            check(value.getPlayerList().getPlayerCount() == 2, "Exactly two real players joined this dedicated server");
            ServerPlayer host = value.getPlayerList().getPlayer(hostId);
            ServerPlayer visitor = value.getPlayerList().getPlayer(guestId);
            host.teleportTo(.5, 80, .5);
            visitor.setGameMode(GameType.SURVIVAL);
            visitor.getInventory().clearContent();
            visitor.getInventory().setItem(9, new ItemStack(Items.EMERALD, 19));
            visitor.getInventory().setSelectedSlot(0);
            visitor.teleportTo(3.5, 80, .5);
            visitor.inventoryMenu.broadcastChanges();
        });
        files.write("players-ready", new JsonObject());
        files.await(context, "guest-inserted", Duration.ofMinutes(1));
        server.waitFor(value -> worn(value, hostId).getItem(0).is(Items.EMERALD) && worn(value, hostId).getItem(0).getCount() == 19);
        context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen && screen.getMenu().bag().getItem(0).is(Items.EMERALD)
                && screen.getMenu().bag().getItem(0).getCount() == 19);
        files.screenshot(context, "host-sees-shared-19");
        files.write("host-observed", new JsonObject());
        server.runOnServer(value -> {
            BagInventory bag = worn(value, hostId);
            bag.updateSettings(tag -> tag.putBoolean("share_access", false));
            BackpackEquipment.setFromInventory(value.getPlayerList().getPlayer(hostId), bag);
        });
        server.waitFor(value -> !(value.getPlayerList().getPlayer(guestId).containerMenu instanceof BackpackMenu));
        try (var fence = server.computeOnServer(value -> new ReopenInteractionFence(value, guestId, hostId))) {
            files.write("access-revoked", new JsonObject());
            files.await(context, "guest-reopen-attempted", Duration.ofMinutes(1));
            fence.awaitObserved(context, server);
            JsonObject denied = new JsonObject();
            denied.addProperty("guest_uuid", guestId.toString());
            denied.addProperty("host_uuid", hostId.toString());
            denied.addProperty("server_tick", fence.observedTick.get());
            files.write("reopen-denied", denied);
            files.await(context, "guest-closed", Duration.ofMinutes(1));
        }

        server.runOnServer(value -> value.getPlayerList().getPlayer(hostId).teleportTo(5.5, 80, .5));
        files.write("source-moved", new JsonObject());
        files.await(context, "guest-moving-audio", Duration.ofMinutes(1));
        JsonObject departure = server.computeOnServer(value -> {
            value.getPlayerList().getPlayer(hostId).teleportTo(300.5, 80, .5);
            return musicTiming(value, hostId);
        });
        files.write("source-out-of-range", departure);
        files.await(context, "guest-silent", Duration.ofMinutes(1));
        audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
        JsonObject reentry = server.computeOnServer(value -> {
            value.getPlayerList().getPlayer(hostId).teleportTo(5.5, 80, .5);
            return musicTiming(value, hostId);
        });
        check(departure.get("song_started").equals(reentry.get("song_started"))
                        && departure.get("song_finish").equals(reentry.get("song_finish")),
                "Leaving and returning to a listener never restarts the server's active song");
        files.write("source-reentered", reentry);
        files.await(context, "guest-reentry-audio", Duration.ofMinutes(1));
        check(server.computeOnServer(value -> musicTiming(value, hostId).get("song_finish")).equals(departure.get("song_finish")),
                "The resumed remote audio retains the original server song deadline");

        server.runOnServer(value -> secondSource(value, "play"));
        files.write("second-source-playing", new JsonObject());
        files.await(context, "guest-two-sources", Duration.ofMinutes(1));
        audio.awaitActive(2, REMOTE_AUDIO_TIMEOUT);
        var wornSound = audio.onlyActive(WORN_TRACK);
        var placedSound = audio.onlyActive(PLACED_TRACK);
        clickButton(context, "Stop");
        server.waitFor(value -> !playing(worn(value, hostId)));
        audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
        audio.requireIsolatedStop(wornSound, placedSound);
        files.write("worn-source-stopped", new JsonObject());
        files.await(context, "guest-isolated-stop", Duration.ofMinutes(1));
        server.runOnServer(value -> secondSource(value, "stop"));
        files.write("all-sources-stopped", new JsonObject());
        audio.awaitActive(0, REMOTE_AUDIO_TIMEOUT);
        JsonObject guestPass = files.await(context, "guest-pass", Duration.ofMinutes(1));
        check(guestPass.get("pid").getAsLong() == guestPid, "The same second JVM completed the observer assertions");
        JsonObject report = new JsonObject();
        report.addProperty("guest_pid", guestPid);
        int storedEmeralds = server.computeOnServer(value -> worn(value, hostId).getItem(0).getCount());
        report.addProperty("stored_emeralds", storedEmeralds);
        report.addProperty("range_round_trip_server_ticks", reentry.get("server_tick").getAsInt() - departure.get("server_tick").getAsInt());
        report.addProperty("channels", audio.channels());
        report.addProperty("checks", "two TCP profiles; guest real crouch/right-click; 19-item mouse transfer visible on both clients; server lease revocation; late moving audio; range exit/reentry; isolated source stop");
        files.write("host-pass", report);
        System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_HOST_PASS " + files.root);
    }

    public static void guest(ClientGameTestContext context) {
        Session files = new Session("guest");
        try (var audio = new ClientAudioProbe(context)) {
            JsonObject ready = files.await(context, "ready", Duration.ofMinutes(3));
            long hostPid = ready.get("pid").getAsLong();
            check(hostPid != ProcessHandle.current().pid(), "The remote host must run in another Minecraft JVM");
            UUID hostId = UUID.fromString(ready.get("host_uuid").getAsString());
            int port = ready.get("port").getAsInt();
            check(port > 0 && port <= 65535, "Use the actual bound TCP port from this run");
            String address = "127.0.0.1:" + port;
            context.runOnClient(client -> ConnectScreen.startConnecting(client.gui.screen(), client, ServerAddress.parseString(address),
                    new ServerData("Fabricated Backpacks acceptance", address, ServerData.Type.OTHER), false, null));
            context.waitFor(client -> client.level != null && client.player != null && client.gui.screen() == null, 2400);
            verifyTcp(context);
            UUID guestId = context.computeOnClient(client -> client.player.getUUID());
            check(!guestId.equals(hostId), "Guest launch must use a distinct --username");
            JsonObject joined = new JsonObject();
            joined.addProperty("guest_uuid", guestId.toString());
            String guestName = context.computeOnClient(client -> client.player.getGameProfile().name());
            joined.addProperty("guest_name", guestName);
            files.write("guest-connected", joined);
            files.await(context, "players-ready", Duration.ofMinutes(1));
            context.waitFor(client -> client.player.getInventory().getItem(9).getCount() == 19 && Math.abs(client.player.getX() - 3.5) < .1, 1200);
            verifyEquipmentPrivacy(context, hostId);
            audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
            var moving = audio.onlyActive(WORN_TRACK);
            context.getInput().lookAt(new BlockPos(0, 81, 0));
            files.screenshot(context, "guest-late-audio");

            openSharedByMouse(context, hostId);
            clickPlayerSlot(context, 9);
            clickSlot(context, 0);
            context.waitFor(client -> client.gui.screen() instanceof BackpackScreen screen && screen.getMenu().bag().getItem(0).is(Items.EMERALD)
                    && screen.getMenu().bag().getItem(0).getCount() == 19 && screen.getMenu().getCarried().isEmpty()
                    && client.player.getInventory().getItem(9).isEmpty());
            files.screenshot(context, "guest-inserts-19");
            verifyEquipmentPrivacy(context, hostId);
            files.write("guest-inserted", new JsonObject());
            files.await(context, "host-observed", Duration.ofMinutes(1));
            files.await(context, "access-revoked", Duration.ofMinutes(1));
            context.waitFor(client -> client.gui.screen() == null && !(client.player.containerMenu instanceof BackpackMenu), 1200);
            crouchClick(context, hostId);
            files.write("guest-reopen-attempted", new JsonObject());
            JsonObject denied = files.await(context, "reopen-denied", Duration.ofMinutes(1));
            check(denied.get("guest_uuid").getAsString().equals(guestId.toString())
                    && denied.get("host_uuid").getAsString().equals(hostId.toString())
                    && denied.get("server_tick").getAsInt() >= 0,
                    "The denial acknowledgment names this guest's observed interaction with this host");
            check(context.computeOnClient(client -> !(client.gui.screen() instanceof BackpackScreen)), "Revoked sharing cannot reopen through another real entity interaction");
            files.screenshot(context, "guest-sharing-revoked");
            files.write("guest-closed", new JsonObject());

            files.await(context, "source-moved", Duration.ofMinutes(1));
            audio.awaitPosition(moving, 5.5, REMOTE_AUDIO_TIMEOUT);
            files.write("guest-moving-audio", new JsonObject());
            files.await(context, "source-out-of-range", Duration.ofMinutes(1));
            audio.awaitActive(0, REMOTE_AUDIO_TIMEOUT);
            files.write("guest-silent", new JsonObject());
            files.await(context, "source-reentered", Duration.ofMinutes(1));
            audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
            var resumed = audio.onlyActive(WORN_TRACK);
            check(resumed != moving && context.computeOnClient(client -> !client.getSoundManager().isActive(moving)),
                    "Retracking creates a fresh instance of the same record while the removed carrier's sound remains stopped");
            verifyEquipmentPrivacy(context, hostId);
            files.write("guest-reentry-audio", new JsonObject());
            files.await(context, "second-source-playing", Duration.ofMinutes(1));
            audio.awaitActive(2, REMOTE_AUDIO_TIMEOUT);
            var wornSound = audio.onlyActive(WORN_TRACK);
            var placedSound = audio.onlyActive(PLACED_TRACK);
            files.screenshot(context, "guest-two-record-sources");
            files.write("guest-two-sources", new JsonObject());
            files.await(context, "worn-source-stopped", Duration.ofMinutes(1));
            audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
            audio.requireIsolatedStop(wornSound, placedSound);
            files.write("guest-isolated-stop", new JsonObject());
            files.await(context, "all-sources-stopped", Duration.ofMinutes(1));
            audio.awaitActive(0, REMOTE_AUDIO_TIMEOUT);
            JsonObject report = new JsonObject();
            report.addProperty("host_pid", hostPid);
            report.addProperty("guest_uuid", guestId.toString());
            report.addProperty("channels", audio.channels());
            report.addProperty("checks", "real TCP client; distinct JVM/profile; actual shared menu and 19-item mouse insert; server-observed revoked reopen denial; pre-existing music delivered to late listener; moving/range/reentry channels; isolated stop");
            report.addProperty("equipment_privacy", "remote EQUIPPED is empty and sanitized VISUAL is present at late join, shared-menu use and entity range re-entry; full contents arrive only through the authorized shared menu");
            files.write("guest-pass", report);
            files.await(context, "host-pass", Duration.ofMinutes(1));
            context.runOnClient(client -> {
                if (client.level != null) { client.level.disconnect(Component.literal("Acceptance complete")); client.disconnectWithSavingScreen(); }
            });
            context.waitFor(client -> client.level == null);
            context.setScreen(TitleScreen::new);
            System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_GUEST_PASS " + files.root);
        } catch (Throwable failure) { files.failure(failure); throw new AssertionError("Real multiplayer guest acceptance failed", failure); }
    }

    private static void setupHost(MinecraftServer server, UUID hostId) {
        ServerPlayer host = server.getPlayerList().getPlayer(hostId);
        var level = host.level();
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
        for (int x = 298; x <= 302; x++) for (int z = -2; z <= 2; z++) level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
        host.getInventory().clearContent();
        host.setGameMode(GameType.SURVIVAL);
        host.teleportTo(.5, 80, .5);
        host.setYRot(90);
        BagInventory bag = musicBag(UpgradeKind.ADVANCED_JUKEBOX, new ItemStack(Items.MUSIC_DISC_BLOCKS));
        bag.dye(0x467b87, 0xe0bb64);
        bag.updateSettings(tag -> tag.putBoolean("share_access", true));
        BackpackEquipment.set(host, bag.stack());
        host.inventoryMenu.broadcastChanges();
        level.setBlockAndUpdate(SECOND_SOURCE, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState());
        BackpackBlockEntity entity = (BackpackBlockEntity)level.getBlockEntity(SECOND_SOURCE);
        entity.setStack(musicBag(UpgradeKind.JUKEBOX, new ItemStack(Items.MUSIC_DISC_13)).stack());
    }
    private static BagInventory musicBag(UpgradeKind kind, ItemStack disc) {
        BagInventory bag = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.GOLD)));
        ItemStack upgrade = new ItemStack(BackpackRegistry.item(kind));
        check(bag.canInstall(0, upgrade), "The music fixture installs a real compatible upgrade");
        bag.upgrades().setItem(0, upgrade);
        bag.upgradeInventory(bag.installedUpgrades().getFirst()).setItem(0, disc);
        return bag;
    }
    private static BagInventory worn(MinecraftServer server, UUID host) {
        ServerPlayer player = server.getPlayerList().getPlayer(host);
        check(player != null, "The real host disconnected; inspect the TCP server/client logs for the primary failure");
        return BackpackEquipment.inventory(player).orElseThrow(() -> new AssertionError("The host's equipped backpack disappeared"));
    }
    private static boolean playing(BagInventory bag) { return bag.settings(bag.installedUpgrades().getFirst()).getBooleanOr("playing", false); }

    private static JsonObject musicTiming(MinecraftServer server, UUID hostId) {
        BagInventory bag = worn(server, hostId);
        check(playing(bag), "The server's worn jukebox remains playing throughout listener tracking changes");
        var state = bag.settings(bag.installedUpgrades().getFirst());
        JsonObject timing = new JsonObject();
        timing.addProperty("server_tick", server.getTickCount());
        timing.addProperty("song_started", state.getLongOr("song_started", -1));
        timing.addProperty("song_finish", state.getLongOr("song_finish", -1));
        return timing;
    }

    /** Scoped observation of a real server callback; it never opens a menu or substitutes an interaction. */
    private static final class ReopenInteractionFence implements AutoCloseable {
        private final UUID guestId;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicInteger observedTick = new AtomicInteger(-1);

        ReopenInteractionFence(MinecraftServer server, UUID guestId, UUID hostId) {
            this.guestId = guestId;
            // Registered after normal callbacks: an incorrectly accepted backpack interaction
            // short-circuits before this observer and cannot produce a denial acknowledgment.
            UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
                if (active.get() && player instanceof ServerPlayer visitor && visitor.level().getServer() == server
                        && visitor.getUUID().equals(guestId) && entity instanceof ServerPlayer wearer
                        && wearer.getUUID().equals(hostId) && visitor.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND) {
                    observedTick.compareAndSet(-1, server.getTickCount());
                }
                return InteractionResult.PASS;
            });
        }

        void awaitObserved(ClientGameTestContext context, TestDedicatedServerContext server) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            do {
                boolean observed = server.computeOnServer(value -> {
                    ServerPlayer guest = value.getPlayerList().getPlayer(guestId);
                    check(guest != null && guest.containerMenu == guest.inventoryMenu,
                            "The server must keep the revoked guest out of every shared menu after its real interaction");
                    return observedTick.get() >= 0;
                });
                if (observed) return;
                context.waitTick();
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("The server did not observe the exact revoked guest-to-host crouch/right-click");
        }

        @Override public void close() { active.set(false); }
    }

    private static void secondSource(MinecraftServer server, String action) {
        BackpackBlockEntity entity = (BackpackBlockEntity)server.overworld().getBlockEntity(SECOND_SOURCE);
        BagInventory bag = entity.inventory();
        check(JukeboxRuntime.action(bag, bag.installedUpgrades().getFirst(), server.overworld(), SECOND_SOURCE, null, action), "The placed music source accepts its action");
        entity.setChanged();
    }
    private static void openSharedByMouse(ClientGameTestContext context, UUID host) {
        crouchClick(context, host);
        context.waitForScreen(BackpackScreen.class);
        check(context.computeOnClient(client -> ((BackpackScreen)client.gui.screen()).getMenu().bag().getItem(0).isEmpty()), "The authorized shared menu opens the original empty storage");
    }
    private static void crouchClick(ClientGameTestContext context, UUID host) {
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        try {
            context.waitTicks(3);
            context.getInput().lookAt(new BlockPos(0, 80, 0));
            context.waitFor(client -> client.hitResult instanceof EntityHitResult hit && hit.getEntity().getUUID().equals(host));
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        } finally { context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT); }
    }
    private static void verifyTcp(ClientGameTestContext context) {
        check(context.computeOnClient(client -> client.getConnection() != null && !client.getConnection().getConnection().isMemoryConnection()
                && client.getConnection().getConnection().getRemoteAddress() instanceof InetSocketAddress), "The client connection is a real TCP socket");
    }
    private static void verifyEquipmentPrivacy(ClientGameTestContext context, UUID host) {
        context.waitFor(client -> client.level.getPlayerByUUID(host) != null && !BackpackEquipment.visual(client.level.getPlayerByUUID(host)).isEmpty(), 1200);
        context.runOnClient(client -> {
            var remote = client.level.getPlayerByUUID(host);
            check(remote.getAttachedOrElse(BackpackEquipment.EQUIPPED, ItemStack.EMPTY).isEmpty(), "Another player's full private equipment attachment must never synchronize");
            ItemStack visual = BackpackEquipment.visual(remote);
            check(!visual.isEmpty() && !visual.has(BagComponents.IDENTITY) && !visual.has(BagComponents.CONTENTS)
                    && !visual.has(BagComponents.UPGRADES) && !visual.has(BagComponents.SETTINGS), "Public appearance includes no private identity, storage, upgrades or settings");
        });
    }
    @SuppressWarnings("unchecked")
    private static int boundPort(MinecraftServer server) throws ReflectiveOperationException {
        // The verified 26.2 listener retains getPort()==0 after binding. Read the actual socket,
        // then update that advertised value before Fabric's public connect() helper uses it.
        var field = ServerConnectionListener.class.getDeclaredField("channels");
        field.setAccessible(true);
        List<ChannelFuture> channels = (List<ChannelFuture>)field.get(server.getConnection());
        synchronized (channels) {
            for (ChannelFuture future : channels) if (future.channel().localAddress() instanceof InetSocketAddress address) {
                check(address.getAddress().isLoopbackAddress() && address.getPort() > 0, "Dedicated acceptance binds only a loopback ephemeral socket");
                return address.getPort();
            }
        }
        throw new AssertionError("Dedicated test server has no bound TCP listener");
    }

    private static final class Session {
        final String role;
        final String runId;
        final Path root;

        Session(String role) {
            this.role = role;
            String requested = System.getProperty("fabricated.backpacks.multiplayerRunId", "");
            runId = UUID.fromString(requested).toString();
            check(runId.equals(requested), "Both clients require the same canonical multiplayerRunId UUID");
            Path base = ClientAcceptanceFiles.ROOT.toAbsolutePath().normalize();
            root = base.resolve("multiplayer-" + runId).normalize();
            check(root.startsWith(base) && !root.equals(base), "Multiplayer evidence stays under the configured evidence root");
        }
        void create() throws IOException {
            for (Path directory = root.getParent(); directory != null; directory = directory.getParent())
                if (Files.isSymbolicLink(directory)) throw new IOException("Acceptance evidence cannot traverse symbolic links");
            Files.createDirectories(root.getParent());
            Files.createDirectory(root);
        }
        void write(String phase, JsonObject data) throws IOException {
            check(phase.matches("[a-z0-9-]+"), "Phase names are fixed safe file stems");
            data.addProperty("run_id", runId);
            data.addProperty("role", role);
            data.addProperty("phase", phase);
            data.addProperty("pid", ProcessHandle.current().pid());
            data.addProperty("recorded_at", System.currentTimeMillis());
            Path destination = root.resolve(phase + ".json").normalize();
            Path temporary = root.resolve("." + phase + "-" + UUID.randomUUID() + ".tmp").normalize();
            check(destination.startsWith(root) && temporary.startsWith(root), "Phase paths cannot escape the run directory");
            check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "A completed phase is never overwritten");
            Files.writeString(temporary, JSON.toJson(data), StandardOpenOption.CREATE_NEW);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temporary, destination); }
        }
        JsonObject await(ClientGameTestContext context, String phase, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            String other = role.equals("host") ? "guest" : "host";
            while (System.nanoTime() < deadline) {
                JsonObject failure = read(other + "-failure");
                if (failure != null) throw new AssertionError("The other Minecraft JVM failed: " + failure.get("failure").getAsString());
                JsonObject record = read(phase);
                if (record != null) return record;
                context.waitTick();
                // Keep real server time advancing while the second JVM starts. A tight accelerated
                // tick loop could otherwise finish the physical record before that client joins.
                Thread.sleep(50);
            }
            throw new AssertionError("Timed out waiting for " + phase + " in run " + runId);
        }
        JsonObject read(String phase) throws IOException {
            Path path = root.resolve(phase + ".json");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            check(Files.size(path) < 64 * 1024, "A coordination record is bounded");
            JsonObject record = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            check(record.get("run_id").getAsString().equals(runId) && record.get("phase").getAsString().equals(phase), "Only records from this exact run are accepted");
            return record;
        }
        void screenshot(ClientGameTestContext context, String name) throws IOException {
            Path image = context.takeScreenshot("multiplayer-" + runId + "-" + name);
            Files.copy(image, root.resolve(name + ".png"));
        }
        void failure(Throwable exception) {
            JsonObject report = new JsonObject();
            report.addProperty("failure", exception.toString());
            var stack = new java.io.StringWriter();
            exception.printStackTrace(new java.io.PrintWriter(stack));
            report.addProperty("stack_trace", stack.toString());
            try { write(role + "-failure", report); }
            catch (Exception recordingFailure) { exception.addSuppressed(recordingFailure); }
        }
    }
}
