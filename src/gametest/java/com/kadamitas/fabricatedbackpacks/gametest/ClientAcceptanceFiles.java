package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** Cross-process evidence lives outside Loom's disposable run directory. Only synthetic test saves are copied. */
final class ClientAcceptanceFiles {
    static final Path ROOT = Path.of(System.getProperty("fabricated.backpacks.evidenceRoot", "client-evidence")).toAbsolutePath().normalize();
    private ClientAcceptanceFiles() {}

    static void beginFull() {
        invalidate("full-pass.json", "restart-pass.json");
    }

    private static void invalidate(String... names) {
        try {
            Files.createDirectories(ROOT);
            for (String name : names) Files.deleteIfExists(ROOT.resolve(name));
        } catch (IOException exception) { throw new AssertionError("Could not invalidate previous client acceptance", exception); }
    }

    static CompoundTag snapshot(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            var ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
            var tag = new CompoundTag();
            tag.put("bag", ItemStack.CODEC.encodeStart(ops, player.getInventory().getItem(0)).getOrThrow());
            tag.put("equipment", ItemStack.OPTIONAL_CODEC.encodeStart(ops, BackpackEquipment.get(player)).getOrThrow());
            tag.putString("player", player.getUUID().toString());
            tag.putLong("writer_pid", ProcessHandle.current().pid());
            return tag;
        });
    }

    static void archive(TestWorldSave save, CompoundTag expected) {
        try {
            Files.createDirectories(ROOT);
            copyTree(save.getSaveDirectory(), ROOT.resolve("restart-world"));
            Files.writeString(ROOT.resolve("restart-expected.snbt"), expected.toString());
            Path bookmarks = FabricLoader.getInstance().getConfigDir().resolve("fabricated-backpacks-browser.json");
            if (Files.isRegularFile(bookmarks)) Files.copy(bookmarks, ROOT.resolve("browser-bookmarks.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) { throw new AssertionError("Could not archive the closed acceptance world", exception); }
    }

    static void restart(ClientGameTestContext context) {
        invalidate("restart-pass.json");
        try {
            CompoundTag expected = TagParser.parseCompoundFully(Files.readString(ROOT.resolve("restart-expected.snbt")));
            BackpackClientGameTests.check(expected.getLongOr("writer_pid", -1) != ProcessHandle.current().pid(),
                    "Restart acceptance must run in a different Minecraft JVM");
            TestWorldSave placeholder;
            try (var created = context.worldBuilder().create()) { placeholder = created.getWorldSave(); }
            copyTree(ROOT.resolve("restart-world"), placeholder.getSaveDirectory());
            Path bookmarks = ROOT.resolve("browser-bookmarks.json");
            if (Files.isRegularFile(bookmarks)) {
                Path target = FabricLoader.getInstance().getConfigDir().resolve("fabricated-backpacks-browser.json");
                Files.createDirectories(target.getParent());
                Files.copy(bookmarks, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var reopened = placeholder.open()) {
                reopened.getConnection().waitForChunksRender();
                context.waitTicks(5);
                reopened.getServer().runOnServer(server -> {
                    var player = reopened.getConnection().getServerPlayer();
                    var ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
                    var bag = ItemStack.CODEC.parse(ops, expected.get("bag")).getOrThrow();
                    var equipment = ItemStack.OPTIONAL_CODEC.parse(ops, expected.get("equipment")).getOrThrow();
                    BackpackClientGameTests.check(player.getUUID().toString().equals(expected.getStringOr("player", "")), "Offline test profile remains the same across JVMs");
                    BackpackClientGameTests.check(ItemStack.matches(player.getInventory().getItem(0), bag), "Every main-backpack component survives a full JVM restart");
                    BackpackClientGameTests.check(ItemStack.matches(BackpackEquipment.get(player), equipment), "Every independent equipment component survives a full JVM restart");
                });
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_B);
                context.waitForScreen(com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen.class);
                context.takeScreenshot("restart-equipped-backpack");
                BackpackClientGameTests.clickButton(context, "Items");
                BackpackClientGameTests.waitBrowser(context);
                BackpackClientGameTests.searchBrowser(context, "@minecraft \"crafting table\"");
                context.waitTicks(30);
                BackpackClientGameTests.clickButton(context, "Crafting Table");
                context.waitFor(client -> client.gui.screen().children().stream()
                        .anyMatch(widget -> widget instanceof net.minecraft.client.gui.components.AbstractWidget button && button.getMessage().getString().equals("★")));
                context.takeScreenshot("restart-browser-bookmarks");
            }
            Files.write(ROOT.resolve("restart-pass.txt"), List.of("PASS: separate Minecraft JVM", "writer PID=" + expected.getLongOr("writer_pid", -1),
                    "reader PID=" + ProcessHandle.current().pid(), "Full item-component equality, equipped slot, saved item bookmark, real menu input"));
            var proof = new com.google.gson.JsonObject();
            proof.addProperty("passed", true);
            proof.addProperty("writer_pid", expected.getLongOr("writer_pid", -1));
            proof.addProperty("reader_pid", ProcessHandle.current().pid());
            copyTree(Path.of("screenshots"), ROOT.resolve("restart-screenshots"));
            Files.writeString(ROOT.resolve("restart-pass.json"), proof.toString());
            System.out.println("FABRICATED_BACKPACKS_RESTART_ACCEPTANCE_PASS " + ROOT);
        } catch (Exception exception) { throw new AssertionError("Cross-JVM restart acceptance failed", exception); }
    }

    static void copyTree(Path source, Path destination) throws IOException {
        Path from = source.toAbsolutePath().normalize();
        Path to = destination.toAbsolutePath().normalize();
        Path saves = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("saves");
        boolean archive = to.equals(ROOT.resolve("restart-world")) || to.equals(ROOT.resolve("full-screenshots"))
                || to.equals(ROOT.resolve("restart-screenshots"));
        if ((!archive && (!to.startsWith(saves) || to.equals(saves)))
                || from.equals(to) || to.startsWith(from) || from.startsWith(to)
                || Files.isSymbolicLink(from) || Files.isSymbolicLink(to))
            throw new IOException("Unsafe acceptance copy roots");
        // Resolve both parents before any directory move. All destinations are synthetic
        // acceptance archives or the disposable test instance's saves, never personal saves.
        Files.createDirectories(to.getParent());
        if (!from.toRealPath().equals(from) || !to.getParent().toRealPath().equals(to.getParent()))
            throw new IOException("Acceptance roots must not traverse symbolic links");
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            try (var existing = Files.walk(to)) {
                if (existing.anyMatch(Files::isSymbolicLink)) throw new IOException("Acceptance destination contains a symbolic link");
            }
        }
        Path staged = Files.createTempDirectory(to.getParent(), "." + to.getFileName() + ".copy-");
        try (var files = Files.walk(from)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("A test save must not contain symbolic links");
                Path target = staged.resolve(from.relativize(file)).normalize();
                if (!target.startsWith(staged)) throw new IOException("Acceptance copy escaped its destination");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
        Path previous = to.resolveSibling("." + to.getFileName() + ".previous-" + UUID.randomUUID());
        if (!previous.getParent().equals(to.getParent()) || !staged.getParent().equals(to.getParent()))
            throw new IOException("Acceptance snapshot move escaped its verified parent");
        boolean retained = Files.exists(to, LinkOption.NOFOLLOW_LINKS);
        if (retained) Files.move(to, previous);
        try { Files.move(staged, to); }
        catch (IOException failure) {
            if (retained && !Files.exists(to, LinkOption.NOFOLLOW_LINKS)) Files.move(previous, to);
            throw failure;
        }
        // Keep the previous synthetic snapshot for diagnosis. Overlay copying could leave
        // old chunks or screenshots behind and would not represent this completed run.
    }
}
