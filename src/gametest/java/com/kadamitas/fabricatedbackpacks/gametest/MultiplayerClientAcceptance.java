package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilter;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.engine.EngineSideMode;
import com.kadamitas.fabricatedbackpacks.client.automation.ConduitScreen;
import com.kadamitas.fabricatedbackpacks.client.automation.SteamEngineScreen;
import com.kadamitas.fabricatedbackpacks.client.automation.SteamEngineSideScreen;
import com.kadamitas.fabricatedbackpacks.client.browser.RegistryPickerScreen;
import team.reborn.energy.api.EnergyStorage;

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
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
import java.util.ArrayList;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.check;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickButton;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickPlayerSlot;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.clickSlot;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.selectUpgrade;

/** Two distinct Minecraft JVMs and a real TCP server; files coordinate actions but never simulate game packets. */
public final class MultiplayerClientAcceptance {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BlockPos SECOND_SOURCE = new BlockPos(0, 80, 6);
    private static final Duration REMOTE_AUDIO_TIMEOUT = Duration.ofSeconds(30);
    private static final ResourceLocation WORN_TRACK = ResourceLocation.withDefaultNamespace("music_disc.blocks");
    private static final ResourceLocation PLACED_TRACK = ResourceLocation.withDefaultNamespace("music_disc.13");
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
                try (var connection = server.connect()) {
                    connection.waitForChunksRender();
                    UUID hostId = server.computeOnServer(value -> connection.getServerPlayer().getUUID());
                    if (files.automationOnly()) {
                        server.runOnServer(value -> {
                            var host = value.getPlayerList().getPlayer(hostId);
                            for (int x = 4; x <= 10; x++) for (int z = 0; z <= 7; z++)
                                value.overworld().setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
                            host.getInventory().clearContent();
                            host.setGameMode(GameType.SURVIVAL);
                            host.teleportTo(5.5, 80, 2.5);
                            host.inventoryMenu.broadcastChanges();
                        });
                        connection.waitForClientboundPackets();
                        verifyTcp(context);
                        JsonObject ready = new JsonObject();
                        ready.addProperty("port", port);
                        ready.addProperty("host_uuid", hostId.toString());
                        String hostName = server.computeOnServer(value -> connection.getServerPlayer().getGameProfile().name());
                        ready.addProperty("host_name", hostName);
                        files.write("ready", ready);
                        automationOnlyHost(context, server, hostId, hostName, files);
                        return;
                    }
                    try (var audio = new ClientAudioProbe(context)) {
                        server.runOnServer(value -> setupHost(value, hostId));
                        connection.waitForClientboundPackets();
                        verifyTcp(context);
                        context.getInput().pressKey(GLFW.GLFW_KEY_B);
                        context.waitForScreen(BackpackScreen.class);
                        selectUpgrade(context, 0);
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
        automationHost(context, server, hostId, guestId, files);
        JsonObject guestPass = files.await(context, "guest-pass", Duration.ofMinutes(1));
        check(guestPass.get("pid").getAsLong() == guestPid, "The same second JVM completed the observer assertions");
        JsonObject report = new JsonObject();
        report.addProperty("guest_pid", guestPid);
        int storedEmeralds = server.computeOnServer(value -> worn(value, hostId).getItem(0).getCount());
        report.addProperty("stored_emeralds", storedEmeralds);
        report.addProperty("range_round_trip_server_ticks", reentry.get("server_tick").getAsInt() - departure.get("server_tick").getAsInt());
        report.addProperty("channels", audio.channels());
        report.addProperty("checks", "two TCP profiles; guest real crouch/right-click; 19-item mouse transfer visible on both clients; server lease revocation; late moving audio; range exit/reentry; isolated source stop");
        report.addProperty("automation", "two native steam-engine menus synchronize resource counters and toggle; guest sees public active/bundle state without private capability contents; actual guest conduit change reaches server; range revokes menu");
        files.write("host-pass", report);
        System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_HOST_PASS " + files.root);
    }

    public static void guest(ClientGameTestContext context) {
        Session files = new Session("guest");
        if (files.automationOnly()) {
            automationOnlyGuest(context, files);
            return;
        }
        try (var audio = new ClientAudioProbe(context)) {
            RemotePeer peer = connectGuest(context, files);
            long hostPid = peer.hostPid();
            UUID hostId = peer.hostId();
            UUID guestId = peer.guestId();
            files.await(context, "players-ready", Duration.ofMinutes(1));
            context.waitFor(client -> client.player.getInventory().getItem(9).getCount() == 19 && Math.abs(client.player.getX() - 3.5) < .1, 1200);
            verifyEquipmentPrivacy(context, hostId);
            audio.awaitActive(1, REMOTE_AUDIO_TIMEOUT);
            var moving = audio.onlyActive(WORN_TRACK);
            context.getInput().lookAt(new BlockPos(0, 81, 0));
            files.screenshot(context, "guest-late-audio");
            List<BackpackClientGameTests.WornFrame> wornFrames = captureRemoteWorn(context, hostId, files);

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
            automationGuest(context, files);
            JsonObject report = new JsonObject();
            report.addProperty("host_pid", hostPid);
            report.addProperty("guest_uuid", guestId.toString());
            report.addProperty("channels", audio.channels());
            report.addProperty("checks", "real TCP client; distinct JVM/profile; actual shared menu and 19-item mouse insert; server-observed revoked reopen denial; pre-existing music delivered to late listener; moving/range/reentry channels; isolated stop");
            report.addProperty("automation", "native engine and conduit mouse interactions; exact81000-droplet split-word menu sync; remote engine toggle, public animation, private capability redaction, far-menu closure");
            report.addProperty("equipment_privacy", "remote EQUIPPED is empty and sanitized VISUAL is present at late join, shared-menu use and entity range re-entry; full contents arrive only through the authorized shared menu");
            report.addProperty("worn_appearance", "Real connected host rendered from rear and side after normal guest walking input; empty hands, expected dyes and no chest armor; guest returns before shared-menu interaction. Geometry still requires visual review.");
            report.add("worn_frames", JSON.toJsonTree(wornFrames));
            files.write("guest-pass", report);
            files.await(context, "host-pass", Duration.ofMinutes(1));
            disconnectGuest(context);
            System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_GUEST_PASS " + files.root);
        } catch (Throwable failure) { files.failure(failure); throw new AssertionError("Real multiplayer guest acceptance failed", failure); }
    }

    private record RemotePeer(long hostPid, UUID hostId, String hostName, UUID guestId, String guestName) { }

    private static RemotePeer connectGuest(ClientGameTestContext context, Session files) throws Exception {
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
        String guestName = context.computeOnClient(client -> client.player.getGameProfile().name());
        JsonObject joined = new JsonObject();
        joined.addProperty("guest_uuid", guestId.toString());
        joined.addProperty("guest_name", guestName);
        files.write("guest-connected", joined);
        return new RemotePeer(hostPid, hostId, ready.get("host_name").getAsString(), guestId, guestName);
    }

    private static void automationOnlyHost(ClientGameTestContext context, TestDedicatedServerContext server,
                                           UUID hostId, String hostName, Session files) throws Exception {
        JsonObject guest = files.await(context, "guest-connected", Duration.ofMinutes(3));
        UUID guestId = UUID.fromString(guest.get("guest_uuid").getAsString());
        long guestPid = guest.get("pid").getAsLong();
        String guestName = guest.get("guest_name").getAsString();
        check(guestPid != ProcessHandle.current().pid() && !guestId.equals(hostId),
                "Automation acceptance requires two actual JVMs and distinct player profiles");
        server.waitFor(value -> value.getPlayerList().getPlayer(guestId) != null, 2400);
        server.runOnServer(value -> {
            check(value.getPlayerList().getPlayerCount() == 2, "Exactly two real players joined the dedicated server");
            var visitor = value.getPlayerList().getPlayer(guestId);
            check(visitor.getGameProfile().name().equals(guestName), "The reported guest is the connected server profile");
            visitor.setGameMode(GameType.SURVIVAL);
            visitor.getInventory().clearContent();
            visitor.inventoryMenu.broadcastChanges();
        });
        automationHost(context, server, hostId, guestId, files);
        JsonObject guestPass = files.await(context, "guest-pass", Duration.ofMinutes(1));
        check(guestPass.get("pid").getAsLong() == guestPid, "The same guest JVM completed automation acceptance");
        JsonObject report = automationReport(context, ProcessHandle.current().pid(), guestPid,
                hostId, hostName, guestId, guestName);
        report.add("server_observations", files.read("automation-host-pass"));
        files.write("host-pass", report);
        System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_HOST_PASS scope=automation " + files.root);
    }

    private static void automationOnlyGuest(ClientGameTestContext context, Session files) {
        try {
            RemotePeer peer = connectGuest(context, files);
            automationGuest(context, files);
            JsonObject report = automationReport(context, peer.hostPid(), ProcessHandle.current().pid(),
                    peer.hostId(), peer.hostName(), peer.guestId(), peer.guestName());
            report.add("peer_server_observations", files.read("automation-host-pass"));
            files.write("guest-pass", report);
            files.await(context, "host-pass", Duration.ofMinutes(1));
            disconnectGuest(context);
            System.out.println("FABRICATED_BACKPACKS_MULTIPLAYER_GUEST_PASS scope=automation " + files.root);
        } catch (Throwable failure) {
            files.failure(failure);
            throw new AssertionError("Real multiplayer automation guest acceptance failed", failure);
        }
    }

    private static JsonObject automationReport(ClientGameTestContext context, long hostPid, long guestPid,
                                               UUID hostId, String hostName, UUID guestId, String guestName) {
        JsonObject report = context.computeOnClient(client -> {
            check(client.getConnection() != null, "The real client remained connected through automation acceptance");
            var connection = client.getConnection().getConnection();
            check(!connection.isMemoryConnection() && connection.getRemoteAddress() instanceof InetSocketAddress,
                    "The completed automation scenario used an actual TCP connection");
            var address = (InetSocketAddress) connection.getRemoteAddress();
            JsonObject network = new JsonObject();
            network.addProperty("transport", "tcp");
            network.addProperty("tcp_address", address.getAddress().getHostAddress());
            network.addProperty("tcp_port", address.getPort());
            return network;
        });
        report.addProperty("host_pid", hostPid);
        report.addProperty("guest_pid", guestPid);
        report.addProperty("host_uuid", hostId.toString());
        report.addProperty("host_name", hostName);
        report.addProperty("guest_uuid", guestId.toString());
        report.addProperty("guest_name", guestName);
        report.addProperty("passed", true);
        report.add("checks", JSON.toJsonTree(List.of("native engine counter and enable synchronization",
                "public active/bundle state with private engine resources withheld",
                "physical endpoint-only conduit menu and remote mode change",
                "engine-to-backpack transfer through the real energy conduit",
                "native wrench machine-side menu and remote permission synchronization",
                "conduit and machine-side menus revoked out of range",
                "remote Survival single-lane mining, retained client identities and continued energy routing",
                "guest registry-picker filter edit synchronizes the host's already-open physical-face menu")));
        return report;
    }

    private static void disconnectGuest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.level != null) { client.level.disconnect(Component.literal("Acceptance complete")); client.disconnectWithSavingScreen(); }
        });
        context.waitFor(client -> client.level == null);
        context.setScreen(TitleScreen::new);
    }

    private static final BlockPos AUTOMATION_ENGINE = new BlockPos(6, 80, 4);
    private static final BlockPos AUTOMATION_PIPE = AUTOMATION_ENGINE.east();
    private static final Vec3 MINING_HOST_POSITION = new Vec3(8.2, 80, 2.5);
    private static final Vec3 MINING_GUEST_POSITION = new Vec3(7.5, 80, 2.5);

    private static void automationHost(ClientGameTestContext context, TestDedicatedServerContext server,
                                       UUID hostId, UUID guestId, Session files) throws Exception {
        closeAutomationScreen(context);
        server.runOnServer(value -> {
            var level = value.overworld();
            for (int x = 5; x <= 22; x++) for (int z = 2; z <= 7; z++)
                level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(AUTOMATION_ENGINE, AutomationRegistry.STEAM_ENGINE.defaultBlockState());
            var engine = (SteamEngineBlockEntity) level.getBlockEntity(AUTOMATION_ENGINE);
            engine.setEnabled(false);
            engine.setItem(0, new ItemStack(Items.COAL));
            engine.setItem(1, new ItemStack(Items.WATER_BUCKET));
            level.setBlockAndUpdate(AUTOMATION_PIPE, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
            var pipe = (ConduitBundleBlockEntity) level.getBlockEntity(AUTOMATION_PIPE);
            for (var kind : ConduitKind.values()) pipe.install(kind);
            BlockPos sink = AUTOMATION_PIPE.east();
            level.setBlockAndUpdate(sink, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState());
            var battery = BackpackTestSupport.bag(BackpackTier.GOLD, UpgradeKind.BATTERY);
            battery.updateSettings(BackpackTestSupport.upgrade(battery, 0), tag -> tag.putBoolean("external_output", false));
            ((BackpackBlockEntity) level.getBlockEntity(sink)).setStack(battery.stack());
            var host = value.getPlayerList().getPlayer(hostId);
            var guest = value.getPlayerList().getPlayer(guestId);
            host.getInventory().setSelectedSlot(0); host.getInventory().setItem(0, ItemStack.EMPTY);
            guest.getInventory().setSelectedSlot(0); guest.getInventory().setItem(0, ItemStack.EMPTY);
            host.teleportTo(5.5, 80, 2.5); guest.teleportTo(6.5, 80, 1.5);
            host.inventoryMenu.broadcastChanges(); guest.inventoryMenu.broadcastChanges();
        });
        server.waitFor(value -> ((SteamEngineBlockEntity)
                value.overworld().getBlockEntity(AUTOMATION_ENGINE)).snapshot().waterDroplets() == 81_000);
        files.write("automation-ready", new JsonObject());
        interactAutomation(context, AUTOMATION_ENGINE);
        context.waitForScreen(SteamEngineScreen.class);
        context.waitFor(client -> ((SteamEngineScreen)client.gui.screen()).getMenu().waterDroplets() == 81_000);
        files.await(context, "automation-guest-engine-open", Duration.ofMinutes(1));
        BackpackClientGameTests.clickButton(context, "Engine: Off");
        files.write("automation-engine-enabled", new JsonObject());
        files.await(context, "automation-guest-observed-work", Duration.ofMinutes(1));
        files.screenshot(context, "host-steam-engine-live-menu");
        BackpackClientGameTests.clickButton(context, "Engine: On");
        server.waitFor(value -> !((SteamEngineBlockEntity)
                value.overworld().getBlockEntity(AUTOMATION_ENGINE)).enabled());
        closeAutomationScreen(context);
        server.runOnServer(value -> {
            value.getPlayerList().getPlayer(hostId).teleportTo(8.2, 80, 2.5);
            value.getPlayerList().getPlayer(guestId).teleportTo(7.5, 80, 2.5);
        });
        files.write("automation-conduit-ready", new JsonObject());
        files.await(context, "automation-guest-conduit-changed", Duration.ofMinutes(1));
        server.waitFor(value -> ((ConduitBundleBlockEntity)
                value.overworld().getBlockEntity(AUTOMATION_PIPE)).mode(ConduitKind.ITEM, Direction.EAST)
                == ConduitMode.BOTH);
        context.waitFor(client -> Math.abs(client.player.getX() - 8.2) < .1 && Math.abs(client.player.getZ() - 2.5) < .1);
        AutomationClientAcceptance.interactInterface(context, AUTOMATION_PIPE, ConduitKind.ITEM, Direction.EAST);
        context.waitForScreen(ConduitScreen.class);
        context.waitFor(client -> ((ConduitScreen)client.gui.screen()).getMenu()
                .mode(ConduitKind.ITEM, Direction.EAST)
                == ConduitMode.BOTH);
        files.screenshot(context, "host-sees-guest-conduit-change");
        JsonObject filterSync = files.automationOnly() ? automationFilterHost(context, server, hostId, guestId, files) : null;
        server.runOnServer(value -> value.getPlayerList().getPlayer(guestId).teleportTo(21.5, 80, 4.5));
        files.write("automation-guest-out-of-range", new JsonObject());
        files.await(context, "automation-guest-menu-revoked", Duration.ofMinutes(1));
        check(server.computeOnServer(value -> {
            var guest = value.getPlayerList().getPlayer(guestId);
            return guest != null && guest.containerMenu == guest.inventoryMenu;
        }), "The distant guest's conduit menu is closed on the server too");
        long deliveredEnergy = server.computeOnServer(value -> EnergyStorage.SIDED.find(value.overworld(),
                AUTOMATION_PIPE.east(), Direction.WEST).getAmount());
        check(deliveredEnergy > 0, "The real dedicated server engine supplied its connected backpack through the native energy conduit");
        closeAutomationScreen(context);
        server.runOnServer(value -> {
            var host = value.getPlayerList().getPlayer(hostId);
            var guest = value.getPlayerList().getPlayer(guestId);
            host.teleportTo(5.5, 80, 2.5); guest.teleportTo(6.5, 80, 1.5);
            for (var player : List.of(host, guest)) {
                player.getInventory().setItem(0, new ItemStack(AutomationRegistry.CONDUIT_WRENCH));
                player.inventoryMenu.broadcastChanges();
            }
        });
        context.waitFor(client -> client.player.getMainHandItem().is(AutomationRegistry.CONDUIT_WRENCH));
        interactAutomation(context, AUTOMATION_ENGINE);
        context.waitForScreen(SteamEngineSideScreen.class);
        files.write("automation-sides-ready", new JsonObject());
        files.await(context, "automation-guest-sides-changed", Duration.ofMinutes(1));
        server.waitFor(value -> ((SteamEngineBlockEntity) value.overworld().getBlockEntity(AUTOMATION_ENGINE))
                .sideMode(ConduitKind.ENERGY, Direction.UP) == EngineSideMode.DISABLED);
        context.waitFor(client -> ((SteamEngineSideScreen) client.gui.screen()).getMenu()
                .mode(ConduitKind.ENERGY, Direction.UP) == EngineSideMode.DISABLED);
        files.screenshot(context, "host-sees-guest-machine-side-change");
        server.runOnServer(value -> value.getPlayerList().getPlayer(guestId).teleportTo(21.5, 80, 4.5));
        files.write("automation-sides-out-of-range", new JsonObject());
        files.await(context, "automation-guest-sides-revoked", Duration.ofMinutes(1));
        check(server.computeOnServer(value -> {
            var guest = value.getPlayerList().getPlayer(guestId);
            return guest != null && guest.containerMenu == guest.inventoryMenu;
        }), "The distant guest's machine-side menu is closed on the server too");
        closeAutomationScreen(context);
        JsonObject observations = new JsonObject();
        observations.addProperty("initial_water_droplets", 81_000);
        observations.addProperty("delivered_energy_fe", deliveredEnergy);
        observations.addProperty("conduit_face", "east");
        observations.addProperty("conduit_item_mode", "BOTH");
        observations.addProperty("engine_energy_up_mode", "DISABLED");
        observations.addProperty("range_revocations", 2);
        if (filterSync != null) observations.add("filter_viewer_sync", filterSync);
        if (files.automationOnly()) observations.add("single_lane_mining",
                automationMiningHost(context, server, hostId, guestId, files));
        files.write("automation-host-pass", observations);
    }

    private static void automationGuest(ClientGameTestContext context, Session files) throws Exception {
        files.await(context, "automation-ready", Duration.ofMinutes(1));
        closeAutomationScreen(context);
        context.waitFor(client -> client.level.getBlockEntity(AUTOMATION_ENGINE) instanceof SteamEngineBlockEntity
                && client.level.getBlockEntity(AUTOMATION_PIPE) instanceof ConduitBundleBlockEntity pipe
                && pipe.installedMask() == 7);
        check(context.computeOnClient(client -> {
            var engine = (SteamEngineBlockEntity) client.level.getBlockEntity(AUTOMATION_ENGINE);
            return engine.snapshot().waterDroplets() == 0 && engine.snapshot().energy() == 0
                    && engine.energyStorage(Direction.NORTH).getAmount() == 0;
        }), "A tracking client sees public geometry without the engine's private stored resources");
        interactAutomation(context, AUTOMATION_ENGINE);
        context.waitForScreen(SteamEngineScreen.class);
        context.waitFor(client -> {
            var menu = ((SteamEngineScreen)client.gui.screen()).getMenu();
            return menu.waterDroplets() == 81_000 && !menu.enabled();
        });
        files.screenshot(context, "guest-engine-full-water-counter");
        files.write("automation-guest-engine-open", new JsonObject());
        files.await(context, "automation-engine-enabled", Duration.ofMinutes(1));
        context.waitFor(client -> {
            var menu = ((SteamEngineScreen)client.gui.screen()).getMenu();
            return menu.enabled() && menu.waterDroplets() < 81_000 && menu.burnRemaining() > 0;
        });
        files.screenshot(context, "guest-sees-host-engine-toggle");
        closeAutomationScreen(context);
        context.waitFor(client -> client.level.getBlockState(AUTOMATION_ENGINE)
                .getValue(SteamEngineBlock.ACTIVE));
        files.write("automation-guest-observed-work", new JsonObject());
        files.await(context, "automation-conduit-ready", Duration.ofMinutes(1));
        context.waitFor(client -> Math.abs(client.player.getX() - 7.5) < .1);
        AutomationClientAcceptance.interactInterface(context, AUTOMATION_PIPE, ConduitKind.ITEM, Direction.EAST);
        context.waitForScreen(ConduitScreen.class);
        check(context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).getMenu().selectedFace()) == Direction.EAST,
                "The remote player opened the actual east interface, without a center-menu shortcut");
        BackpackClientGameTests.clickButton(context, "Item Conduit: East: Insert");
        context.waitFor(client -> ((ConduitScreen)client.gui.screen()).getMenu()
                .mode(ConduitKind.ITEM, Direction.EAST)
                == ConduitMode.BOTH);
        files.screenshot(context, "guest-changes-conduit-endpoint");
        files.write("automation-guest-conduit-changed", new JsonObject());
        if (files.automationOnly()) automationFilterGuest(context, files);
        files.await(context, "automation-guest-out-of-range", Duration.ofMinutes(1));
        context.waitFor(client -> client.gui.screen() == null);
        files.write("automation-guest-menu-revoked", new JsonObject());
        files.await(context, "automation-sides-ready", Duration.ofMinutes(1));
        context.waitFor(client -> client.player.getMainHandItem().is(AutomationRegistry.CONDUIT_WRENCH)
                && Math.abs(client.player.getX() - 6.5) < .1);
        interactAutomation(context, AUTOMATION_ENGINE);
        context.waitForScreen(SteamEngineSideScreen.class);
        BackpackClientGameTests.clickButton(context, "Face: Up");
        context.waitFor(client -> ((SteamEngineSideScreen) client.gui.screen()).getMenu().selectedFace() == Direction.UP);
        BackpackClientGameTests.clickButton(context, "Energy: Up: Output");
        context.waitFor(client -> ((SteamEngineSideScreen) client.gui.screen()).getMenu()
                .mode(ConduitKind.ENERGY, Direction.UP) == EngineSideMode.DISABLED);
        files.screenshot(context, "guest-changes-engine-output-face");
        files.write("automation-guest-sides-changed", new JsonObject());
        files.await(context, "automation-sides-out-of-range", Duration.ofMinutes(1));
        context.waitFor(client -> client.gui.screen() == null);
        files.write("automation-guest-sides-revoked", new JsonObject());
        if (files.automationOnly()) automationMiningGuest(context, files);
        files.await(context, "automation-host-pass", Duration.ofMinutes(1));
    }

    private static final ResourceLocation FILTERED_ITEM = ResourceLocation.withDefaultNamespace("cobblestone");
    private record FilterViewers(ConduitBundleBlockEntity entity, ConduitMenu host, ConduitMenu guest,
                                 List<ConduitFilter> policies, ItemStack hostCursor, ItemStack guestCursor) {}

    private static ConduitFilter multiplayerItemFilter() {
        return ConduitFilter.EMPTY.withMode(ConduitFilterMode.ALLOW).withEntry(0, FILTERED_ITEM);
    }

    private static JsonObject automationFilterHost(ClientGameTestContext context, TestDedicatedServerContext server,
                                                    UUID hostId, UUID guestId, Session files) throws Exception {
        ConduitScreen observer = context.computeOnClient(client -> (ConduitScreen) client.gui.screen());
        clickButton(context, "Item Conduit: East: Filters");
        context.waitFor(client -> client.gui.screen() == observer
                && observer.selectedFilterKind().orElse(null) == ConduitKind.ITEM && observer.filterTargets().size() == 9);
        check(context.computeOnClient(client -> observer.getMenu().filter(ConduitKind.ITEM).equals(ConduitFilter.EMPTY)),
                "The host's existing face menu starts with an empty, disabled item filter");
        FilterViewers viewers = server.computeOnServer(value -> {
            var entity = (ConduitBundleBlockEntity) value.overworld().getBlockEntity(AUTOMATION_PIPE);
            var host = value.getPlayerList().getPlayer(hostId);
            var guest = value.getPlayerList().getPlayer(guestId);
            check(entity != null && entity.current() && host.containerMenu instanceof ConduitMenu && guest.containerMenu instanceof ConduitMenu,
                    "Both real players concurrently hold native menus on the existing conduit");
            var hostMenu = (ConduitMenu) host.containerMenu;
            var guestMenu = (ConduitMenu) guest.containerMenu;
            for (var menu : List.of(hostMenu, guestMenu))
                check(menu.position().equals(AUTOMATION_PIPE) && menu.selectedFace() == Direction.EAST
                                && menu.filter(ConduitKind.ITEM).equals(ConduitFilter.EMPTY),
                        "Both native menus are bound to the same physical east face before the guest's edit");
            var policies = new ArrayList<ConduitFilter>();
            for (var kind : List.of(ConduitKind.ITEM, ConduitKind.FLUID)) for (var face : Direction.values())
                policies.add(entity.filter(kind, face));
            return new FilterViewers(entity, hostMenu, guestMenu, List.copyOf(policies),
                    hostMenu.getCarried().copy(), guestMenu.getCarried().copy());
        });
        check(context.computeOnClient(client -> observer.getMenu().containerId) == viewers.host().containerId,
                "The observing screen uses the host's current server menu ID");
        JsonObject ready = new JsonObject();
        ready.addProperty("kind", "item"); ready.addProperty("face", "east");
        ready.addProperty("host_menu_id", viewers.host().containerId);
        ready.addProperty("guest_menu_id", viewers.guest().containerId);
        ready.addProperty("initial_filter_mode", "OFF"); ready.addProperty("initial_entries", 0);
        files.write("automation-filter-observer-ready", ready);
        JsonObject edited = files.await(context, "automation-guest-filter-edited", Duration.ofMinutes(1));
        check(edited.get("menu_id").getAsInt() == viewers.guest().containerId
                        && edited.get("interaction").getAsString().equals("mouse_registry_picker")
                        && edited.get("query").getAsString().equals(FILTERED_ITEM.toString())
                        && edited.get("same_menu").getAsBoolean(),
                "The guest completed real picker input without replacing its native menu");
        ConduitFilter expected = multiplayerItemFilter();
        server.waitFor(value -> viewers.entity().filter(ConduitKind.ITEM, Direction.EAST).equals(expected), 200);
        server.runOnServer(value -> {
            check(value.overworld().getBlockEntity(AUTOMATION_PIPE) == viewers.entity()
                            && value.getPlayerList().getPlayer(hostId).containerMenu == viewers.host()
                            && value.getPlayerList().getPlayer(guestId).containerMenu == viewers.guest(),
                    "The actual filter edit preserves the physical conduit and both already-open server menus");
            int index = 0;
            for (var kind : List.of(ConduitKind.ITEM, ConduitKind.FLUID)) for (var face : Direction.values()) {
                ConduitFilter previous = viewers.policies().get(index++);
                check(viewers.entity().filter(kind, face).equals(kind == ConduitKind.ITEM && face == Direction.EAST ? expected : previous),
                        "The guest's edit changes only the selected item policy, preserving other faces and fluid policies: " + kind + " " + face);
            }
            check(ItemStack.matches(viewers.host().getCarried(), viewers.hostCursor())
                            && ItemStack.matches(viewers.guest().getCarried(), viewers.guestCursor()),
                    "Registry ghosts do not grant or consume either player's cursor stack");
        });
        context.waitFor(client -> client.gui.screen() == observer && client.player.containerMenu == observer.getMenu()
                && observer.getMenu().selectedFace() == Direction.EAST && observer.getMenu().filter(ConduitKind.ITEM).equals(expected), 200);
        context.waitFor(client -> observer.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.visible && widget.active).map(widget -> widget.getMessage().getString()).toList()
                .containsAll(List.of("Item Conduit: East: Filter Allow", "Item Conduit: East: Filter 1: Cobblestone")));
        files.screenshot(context, "host-live-conduit-filter-viewer-sync");
        JsonObject result = new JsonObject();
        result.addProperty("kind", "item"); result.addProperty("face", "east");
        result.addProperty("mode", "ALLOW"); result.addProperty("ghost_slot", 0); result.addProperty("ghost_id", FILTERED_ITEM.toString());
        result.addProperty("host_menu_id", viewers.host().containerId); result.addProperty("guest_menu_id", viewers.guest().containerId);
        result.addProperty("same_host_menu", true); result.addProperty("same_guest_menu", true);
        result.addProperty("same_physical_entity", true); result.addProperty("unchanged_policies", 11);
        result.addProperty("cursors_unchanged", true);
        files.write("automation-filter-viewer-synced", result.deepCopy());
        return result;
    }

    private static void automationFilterGuest(ClientGameTestContext context, Session files) throws Exception {
        JsonObject observer = files.await(context, "automation-filter-observer-ready", Duration.ofMinutes(1));
        ConduitScreen editor = context.computeOnClient(client -> (ConduitScreen) client.gui.screen());
        ItemStack cursor = context.computeOnClient(client -> editor.getMenu().getCarried().copy());
        check(context.computeOnClient(client -> client.player.containerMenu == editor.getMenu()
                        && editor.getMenu().containerId == observer.get("guest_menu_id").getAsInt()
                        && editor.getMenu().selectedFace() == Direction.EAST
                        && editor.getMenu().filter(ConduitKind.ITEM).equals(ConduitFilter.EMPTY)),
                "The guest starts the edit in the original shared physical-face menu");
        clickButton(context, "Item Conduit: East: Filters");
        clickButton(context, "Item Conduit: East: Filter Off");
        context.waitFor(client -> client.gui.screen() == editor && editor.getMenu().filter(ConduitKind.ITEM).mode() == ConduitFilterMode.ALLOW);
        clickButton(context, "Item Conduit: East: Filter 1: Empty");
        context.waitForScreen(RegistryPickerScreen.class);
        check(context.computeOnClient(client -> client.player.containerMenu == editor.getMenu()),
                "The built-in picker retains the actual remote conduit menu");
        double[] searchPosition = context.computeOnClient(client -> {
            EditBox search = client.gui.screen().children().stream().filter(EditBox.class::isInstance)
                    .map(EditBox.class::cast).filter(box -> box.visible && box.active).findFirst().orElseThrow();
            check(search.getValue().isEmpty(), "A fresh registry picker starts with an empty query");
            return new double[]{search.getX() + 8, search.getY() + search.getHeight() / 2.0};
        });
        BackpackClientGameTests.clickAt(context, searchPosition[0], searchPosition[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.getInput().typeChars(FILTERED_ITEM.toString());
        context.waitFor(client -> client.gui.screen() instanceof RegistryPickerScreen
                && client.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .anyMatch(box -> box.getValue().equals(FILTERED_ITEM.toString()))
                && client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .anyMatch(widget -> widget.visible && widget.active && widget.getMessage().getString().equals("Cobblestone")), 400);
        files.screenshot(context, "guest-conduit-filter-registry-picker");
        clickButton(context, "Cobblestone");
        context.waitFor(client -> client.gui.screen() == editor && client.player.containerMenu == editor.getMenu()
                && editor.getMenu().filter(ConduitKind.ITEM).equals(multiplayerItemFilter()), 200);
        check(context.computeOnClient(client -> ItemStack.matches(editor.getMenu().getCarried(), cursor)),
                "Actual picker selection installs only a ghost and preserves the guest's cursor");
        JsonObject edited = new JsonObject();
        edited.addProperty("interaction", "mouse_registry_picker"); edited.addProperty("query", FILTERED_ITEM.toString());
        edited.addProperty("menu_id", observer.get("guest_menu_id").getAsInt()); edited.addProperty("same_menu", true);
        edited.addProperty("kind", "item"); edited.addProperty("face", "east");
        edited.addProperty("mode", "ALLOW"); edited.addProperty("ghost_slot", 0); edited.addProperty("ghost_id", FILTERED_ITEM.toString());
        files.write("automation-guest-filter-edited", edited);
        JsonObject synchronizedViewer = files.await(context, "automation-filter-viewer-synced", Duration.ofMinutes(1));
        check(synchronizedViewer.get("same_host_menu").getAsBoolean()
                        && synchronizedViewer.get("host_menu_id").getAsInt() == observer.get("host_menu_id").getAsInt()
                        && synchronizedViewer.get("ghost_id").getAsString().equals(FILTERED_ITEM.toString()),
                "The host confirmed the guest's exact registry selection in its original open menu");
    }

    private static JsonObject automationMiningHost(ClientGameTestContext context, TestDedicatedServerContext server,
                                                   UUID hostId, UUID guestId, Session files) throws Exception {
        // Positions and empty hands are fixtures. Only the guest's normal mouse input performs the break.
        server.runOnServer(value -> {
            for (UUID id : List.of(hostId, guestId)) {
                var player = value.getPlayerList().getPlayer(id);
                Vec3 position = id.equals(hostId) ? MINING_HOST_POSITION : MINING_GUEST_POSITION;
                player.teleportTo(position.x, position.y, position.z);
                player.setDeltaMovement(Vec3.ZERO);
                player.getInventory().setSelectedSlot(0);
                player.getInventory().setItem(0, ItemStack.EMPTY);
                player.inventoryMenu.broadcastChanges();
            }
            check(value.getPlayerList().getPlayer(guestId).gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                    "The remote strand is mined by an actual Survival player");
        });
        var originalServer = server.computeOnServer(value ->
                (ConduitBundleBlockEntity) value.overworld().getBlockEntity(AUTOMATION_PIPE));
        check(server.computeOnServer(value -> originalServer.installedMask()) == 7,
                "Remote mining starts with all three installed lanes");
        long[] dropsBefore = server.computeOnServer(value -> java.util.Arrays.stream(ConduitKind.values())
                .mapToLong(kind -> recoverableAutomationConduits(value, kind)).toArray());
        context.waitFor(client -> client.player.position().distanceToSqr(MINING_HOST_POSITION) < 1.0e-6
                && client.player.getMainHandItem().isEmpty()
                && client.level.getBlockEntity(AUTOMATION_PIPE) instanceof ConduitBundleBlockEntity pipe
                && pipe.installedMask() == 7);
        var originalHost = context.computeOnClient(client -> client.level.getBlockEntity(AUTOMATION_PIPE));
        files.write("automation-mining-ready", new JsonObject());
        files.await(context, "automation-guest-mining-aimed", Duration.ofMinutes(1));
        server.waitFor(value -> {
            var guest = value.getPlayerList().getPlayer(guestId);
            HitResult picked = guest.pick(guest.blockInteractionRange(), 1.0F, false);
            return picked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(AUTOMATION_PIPE)
                    && ConduitGeometry.hitKind(originalServer.visualState(),
                    hit.getLocation().subtract(Vec3.atLowerCornerOf(AUTOMATION_PIPE)), hit.getDirection())
                    .orElse(null) == ConduitKind.FLUID;
        }, 100);
        files.write("automation-mining-target-confirmed", new JsonObject());
        JsonObject guestMined = files.await(context, "automation-guest-mined-fluid", Duration.ofMinutes(1));
        check(guestMined.get("mask_before").getAsInt() == 7 && guestMined.get("mask_after").getAsInt() == 5
                        && guestMined.get("client_identity_retained").getAsBoolean(),
                "The guest observed its own prediction retaining the two unmined lanes");
        server.waitFor(value -> value.overworld().getBlockEntity(AUTOMATION_PIPE) == originalServer
                && originalServer.installedMask() == 5, 100);
        context.waitFor(client -> client.level.getBlockEntity(AUTOMATION_PIPE) instanceof ConduitBundleBlockEntity pipe
                && pipe.installedMask() == 5);
        check(context.computeOnClient(client -> client.level.getBlockEntity(AUTOMATION_PIPE) == originalHost),
                "The observing host retains its live bundle when the guest mines one strand");
        check(server.computeOnServer(value -> originalServer.mode(ConduitKind.ITEM, Direction.EAST) == ConduitMode.BOTH
                        && originalServer.mode(ConduitKind.ENERGY, Direction.WEST) == ConduitMode.BOTH
                        && originalServer.mode(ConduitKind.ENERGY, Direction.EAST) == ConduitMode.BOTH),
                "Mining fluid preserves the surviving item and energy face settings");
        JsonObject dropDeltas = new JsonObject();
        for (ConduitKind kind : ConduitKind.values()) {
            long delta = server.computeOnServer(value -> recoverableAutomationConduits(value, kind)) - dropsBefore[kind.ordinal()];
            check(delta == (kind == ConduitKind.FLUID ? 1 : 0),
                    "Remote fluid mining returns exactly its one drop and no untouched " + kind + " strand");
            dropDeltas.addProperty(kind.name().toLowerCase(java.util.Locale.ROOT), delta);
        }
        context.getInput().lookAt(AUTOMATION_PIPE);
        context.waitTicks(3);
        files.screenshot(context, "host-sees-guest-fluid-lane-mined");
        long energyBefore = server.computeOnServer(MultiplayerClientAcceptance::automationSinkEnergy);
        Vec3 enginePosition = new Vec3(5.5, 80, 2.5);
        server.runOnServer(value -> {
            var host = value.getPlayerList().getPlayer(hostId);
            host.teleportTo(enginePosition.x, enginePosition.y, enginePosition.z);
            host.setDeltaMovement(Vec3.ZERO);
        });
        context.waitFor(client -> client.player.position().distanceToSqr(enginePosition) < 1.0e-6);
        interactAutomation(context, AUTOMATION_ENGINE);
        context.waitForScreen(SteamEngineScreen.class);
        context.waitFor(client -> !((SteamEngineScreen) client.gui.screen()).getMenu().enabled());
        BackpackClientGameTests.clickButton(context, "Engine: Off");
        server.waitFor(value -> automationSinkEnergy(value) > energyBefore, 200);
        context.waitFor(client -> ((SteamEngineScreen) client.gui.screen()).getMenu().enabled());
        BackpackClientGameTests.clickButton(context, "Engine: On");
        server.waitFor(value -> !((SteamEngineBlockEntity) value.overworld().getBlockEntity(AUTOMATION_ENGINE)).enabled());
        long energyAfter = server.computeOnServer(MultiplayerClientAcceptance::automationSinkEnergy);
        check(energyAfter > energyBefore, "New energy reaches the sink after the remote fluid strand was removed");
        check(server.computeOnServer(value -> value.overworld().getBlockEntity(AUTOMATION_PIPE) == originalServer
                        && originalServer.installedMask() == 5),
                "Continued energy delivery uses the original two-lane bundle");
        closeAutomationScreen(context);
        JsonObject result = new JsonObject();
        result.addProperty("mask_before", 7);
        result.addProperty("mask_after", 5);
        result.addProperty("removed_kind", "fluid");
        result.addProperty("server_identity_retained", true);
        result.addProperty("host_identity_retained", true);
        result.addProperty("guest_identity_retained", guestMined.get("client_identity_retained").getAsBoolean());
        result.add("drop_deltas", dropDeltas);
        result.addProperty("sink_energy_before_fe", energyBefore);
        result.addProperty("sink_energy_after_fe", energyAfter);
        result.addProperty("delivered_after_removal_fe", energyAfter - energyBefore);
        files.write("automation-mining-energy-resumed", result.deepCopy());
        files.await(context, "automation-guest-mining-complete", Duration.ofMinutes(1));
        return result;
    }

    private static void automationMiningGuest(ClientGameTestContext context, Session files) throws Exception {
        files.await(context, "automation-mining-ready", Duration.ofMinutes(1));
        context.waitFor(client -> client.gui.screen() == null && client.player.getMainHandItem().isEmpty()
                && client.player.position().distanceToSqr(MINING_GUEST_POSITION) < 1.0e-6
                && client.level.getBlockEntity(AUTOMATION_PIPE) instanceof ConduitBundleBlockEntity pipe
                && pipe.installedMask() == 7);
        var original = context.computeOnClient(client -> client.level.getBlockEntity(AUTOMATION_PIPE));
        AutomationClientAcceptance.aimAtConduit(context, AUTOMATION_PIPE, ConduitKind.FLUID);
        files.write("automation-guest-mining-aimed", new JsonObject());
        files.await(context, "automation-mining-target-confirmed", Duration.ofMinutes(1));
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        try {
            context.waitFor(client -> client.level.getBlockEntity(AUTOMATION_PIPE) instanceof ConduitBundleBlockEntity pipe
                    && pipe.installedMask() == 5, 100);
        } finally {
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        check(context.computeOnClient(client -> client.level.getBlockEntity(AUTOMATION_PIPE) == original),
                "Normal guest mining prediction retains its live bundle until the lane update arrives");
        JsonObject mined = new JsonObject();
        mined.addProperty("interaction", "survival_left_mouse");
        mined.addProperty("mask_before", 7);
        mined.addProperty("mask_after", 5);
        mined.addProperty("client_identity_retained", true);
        files.write("automation-guest-mined-fluid", mined);
        JsonObject resumed = files.await(context, "automation-mining-energy-resumed", Duration.ofMinutes(1));
        check(resumed.get("sink_energy_after_fe").getAsLong() > resumed.get("sink_energy_before_fe").getAsLong(),
                "The dedicated server reports new energy delivery after this guest's actual break");
        check(context.computeOnClient(client -> client.level.getBlockEntity(AUTOMATION_PIPE) == original
                        && ((ConduitBundleBlockEntity) original).installedMask() == 5),
                "The guest still sees the same item and energy strands after routing resumes");
        context.getInput().lookAt(AUTOMATION_PIPE);
        context.waitTicks(3);
        files.screenshot(context, "guest-mined-fluid-lane-remaining-two");
        files.write("automation-guest-mining-complete", new JsonObject());
    }

    private static long recoverableAutomationConduits(MinecraftServer server, ConduitKind kind) {
        long carried = server.getPlayerList().getPlayers().stream().mapToLong(player ->
                BackpackTestSupport.count(player.getInventory(), AutomationRegistry.conduit(kind))).sum();
        return carried + server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(AUTOMATION_PIPE).inflate(4),
                entity -> entity.getItem().is(AutomationRegistry.conduit(kind))).stream()
                .mapToLong(entity -> entity.getItem().getCount()).sum();
    }

    private static long automationSinkEnergy(MinecraftServer server) {
        EnergyStorage sink = EnergyStorage.SIDED.find(server.overworld(), AUTOMATION_PIPE.east(), Direction.WEST);
        check(sink != null, "The actual backpack sink retains its sided energy capability");
        return sink.getAmount();
    }

    private static void closeAutomationScreen(ClientGameTestContext context) {
        if (context.computeOnClient(client -> client.gui.screen() != null)) {
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);
        }
    }

    private static void interactAutomation(ClientGameTestContext context, BlockPos position) {
        context.getInput().lookAt(position);
        context.waitTicks(4);
        context.waitFor(client -> client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getBlockPos().equals(position));
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(4);
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

    private static List<BackpackClientGameTests.WornFrame> captureRemoteWorn(ClientGameTestContext context, UUID host, Session files) throws IOException {
        // The host is already waiting at the existing guest-inserted barrier. No new phase, teleport,
        // player, camera entity or render state is introduced; only the real guest walks around it.
        Vec3 previous = context.computeOnClient(client -> client.player.position());
        float[] look = context.computeOnClient(client -> new float[]{client.player.getYRot(), client.player.getXRot()});
        CameraType camera = context.computeOnClient(client -> client.options.getCameraType());
        boolean hudHidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        var frames = new ArrayList<BackpackClientGameTests.WornFrame>();
        try (var probe = new BackpackClientGameTests.WornFrameProbe(context, host)) {
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            BackpackClientGameTests.hideCaptureHud(context, true);
            for (boolean rear : new boolean[]{true, false}) {
                Vec3 target = context.computeOnClient(client -> {
                    var remote = client.level.getPlayerByUUID(host);
                    double yaw = Math.toRadians(remote.yBodyRot);
                    return remote.position().add(rear ? 3.2 * Math.sin(yaw) : 3.2 * Math.cos(yaw), 0,
                            rear ? -3.2 * Math.cos(yaw) : 3.2 * Math.sin(yaw));
                });
                walkCaptureTo(context, target.x, target.z);
                float[] facing = context.computeOnClient(client -> {
                    Vec3 direction = client.level.getPlayerByUUID(host).position().add(0, 1.0, 0).subtract(client.player.getEyePosition());
                    return new float[]{(float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                            (float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)))};
                });
                context.getInput().lookAt(facing[0], facing[1]);
                context.waitTicks(4);
                verifyEquipmentPrivacy(context, host);
                check(context.computeOnClient(client -> client.gui.screen() == null && client.gui.hud.isHidden()
                        && client.player.getMainHandItem().isEmpty() && client.player.getOffhandItem().isEmpty()
                        && client.level.getPlayerByUUID(host).getMainHandItem().isEmpty()
                        && client.level.getPlayerByUUID(host).getOffhandItem().isEmpty()),
                        "The connected player capture uses empty hands and native F1 HUD/toast hiding");
                long before = probe.sequence();
                files.screenshot(context, "guest-worn-dyed-" + (rear ? "rear" : "side"));
                var frame = probe.after(before);
                frame.require(false, 0x467b87, 0xe0bb64, false);
                check(rear ? frame.cameraFacingDot() < -.85 : Math.abs(frame.cameraFacingDot()) < .35,
                        "The actual remote-player frame must be captured from the requested " + (rear ? "rear" : "side") + ": " + frame);
                frames.add(frame);
            }
        } finally {
            try { walkCaptureTo(context, previous.x, previous.z); }
            finally {
                context.getInput().releaseKey(GLFW.GLFW_KEY_W);
                context.getInput().lookAt(look[0], look[1]);
                context.runOnClient(client -> client.options.setCameraType(camera));
                BackpackClientGameTests.hideCaptureHud(context, hudHidden);
            }
        }
        return List.copyOf(frames);
    }

    private static void walkCaptureTo(ClientGameTestContext context, double x, double z) {
        double[] offset = context.computeOnClient(client -> new double[]{x - client.player.getX(), z - client.player.getZ()});
        if (offset[0] * offset[0] + offset[1] * offset[1] < .04) return;
        context.getInput().lookAt((float) Math.toDegrees(Math.atan2(-offset[0], offset[1])), 0F);
        context.getInput().holdKey(GLFW.GLFW_KEY_W);
        try {
            context.waitFor(client -> {
                double dx = x - client.player.getX(), dz = z - client.player.getZ();
                return dx * dx + dz * dz < .16;
            }, 120);
        } finally { context.getInput().releaseKey(GLFW.GLFW_KEY_W); }
        context.waitTicks(4);
        check(context.computeOnClient(client -> {
            double dx = x - client.player.getX(), dz = z - client.player.getZ();
            return dx * dx + dz * dz < .36;
        }), "Normal walking reaches the bounded camera position and releases movement before the capture");
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
        final String scope;
        final Path root;
        final List<String> screenshots = new ArrayList<>();

        Session(String role) {
            this.role = role;
            scope = System.getProperty("fabricated.backpacks.multiplayerScope", "full");
            check(scope.equals("full") || scope.equals("automation"), "Multiplayer scope must be full or automation");
            String requested = System.getProperty("fabricated.backpacks.multiplayerRunId", "");
            runId = UUID.fromString(requested).toString();
            check(runId.equals(requested), "Both clients require the same canonical multiplayerRunId UUID");
            Path base = ClientAcceptanceFiles.ROOT.toAbsolutePath().normalize();
            root = base.resolve("multiplayer-" + runId).normalize();
            check(root.startsWith(base) && !root.equals(base), "Multiplayer evidence stays under the configured evidence root");
        }
        boolean automationOnly() { return scope.equals("automation"); }
        void create() throws IOException {
            for (Path directory = root.getParent(); directory != null; directory = directory.getParent())
                if (Files.isSymbolicLink(directory)) throw new IOException("Acceptance evidence cannot traverse symbolic links");
            Files.createDirectories(root.getParent());
            Files.createDirectory(root);
        }
        void write(String phase, JsonObject data) throws IOException {
            check(phase.matches("[a-z0-9-]+"), "Phase names are fixed safe file stems");
            data.addProperty("run_id", runId);
            data.addProperty("scope", scope);
            data.addProperty("role", role);
            data.addProperty("phase", phase);
            data.addProperty("pid", ProcessHandle.current().pid());
            data.addProperty("recorded_at", System.currentTimeMillis());
            if (phase.equals(role + "-pass")) data.add("screenshots", JSON.toJsonTree(List.copyOf(screenshots)));
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
            check(record.get("run_id").getAsString().equals(runId) && record.get("phase").getAsString().equals(phase)
                    && record.get("scope").getAsString().equals(scope), "Only records from this exact run and scope are accepted");
            return record;
        }
        void screenshot(ClientGameTestContext context, String name) throws IOException {
            Path image = context.takeScreenshot("multiplayer-" + runId + "-" + name);
            Files.copy(image, root.resolve(name + ".png"));
            screenshots.add(name + ".png");
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
