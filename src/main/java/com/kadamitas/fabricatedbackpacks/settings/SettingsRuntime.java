package com.kadamitas.fabricatedbackpacks.settings;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;

/** Private per-player settings and bounded, permission-checked settings-pack export. */
public final class SettingsRuntime {
    public static final AttachmentType<CustomData> PLAYER_SETTINGS = AttachmentRegistry.create(BackpackRegistry.id("player_settings"),
            builder -> builder.initializer(() -> CustomData.EMPTY).persistent(CustomData.CODEC).copyOnDeath());
    private static final Set<String> PREFERENCES = Set.of("memory_components", "keep_tab", "keep_search", "shift_into_tab", "share_access", "sort_order");
    private static final int MAX_TEMPLATES = 32;
    private static final int MAX_BYTES = 128 * 1024;
    private static final Map<ServerPlayer, Preview> PREVIEWS = new WeakHashMap<>();
    private record Preview(String bagIdentity, String name, SettingsTemplate template) {}
    private SettingsRuntime() {}
    public static void initialize() {}

    public static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,47}"); }
    private static CompoundTag playerData(ServerPlayer player) { return player.getAttachedOrElse(PLAYER_SETTINGS, CustomData.EMPTY).copyTag(); }

    public static CompoundTag effective(BagInventory bag, ServerPlayer player) {
        CompoundTag result = NbtAccess.getCompoundOrEmpty(playerData(player), "defaults").copy();
        CompoundTag bagSettings = bag.settings();
        bagSettings.getAllKeys().forEach(key -> result.put(key, bagSettings.get(key).copy()));
        return result;
    }
    public static CustomData view(BagInventory bag, ServerPlayer player) {
        CompoundTag result = effective(bag, player);
        ListTag names = new ListTag();
        names(player).forEach(name -> names.add(StringTag.valueOf(name)));
        result.put("template_names", names);
        Preview preview = PREVIEWS.get(player);
        if (preview != null && preview.bagIdentity().equals(bag.identity())) {
            result.putString("template_preview", preview.name() + ": " + preview.template().memory().entries().size()
                    + " memory slots, " + preview.template().upgrades().size() + " upgrade settings");
        }
        return CustomData.of(result);
    }
    public static List<String> names(ServerPlayer player) {
        List<String> names = new ArrayList<>(NbtAccess.getCompoundOrEmpty(playerData(player), "templates").getAllKeys().stream().sorted().toList());
        player.level().getServer().getResourceManager().listResources("backpack_settings", id -> id.getPath().endsWith(".snbt")).keySet().stream()
                .sorted().map(id -> id.getNamespace() + ":" + id.getPath().substring("backpack_settings/".length(), id.getPath().length() - 5)).forEach(names::add);
        return List.copyOf(names);
    }
    public static boolean action(BagInventory bag, ServerPlayer player, String action, int value, String text) {
        try {
            switch (action) {
                case "setting" -> {
                    if (text.equals("sort_order") || !PREFERENCES.contains(text) && !Set.of("inception_nested_first", "inception_inner_upgrades", "inception_outer_inventory").contains(text)) return false;
                    boolean current = NbtAccess.getBooleanOr(effective(bag, player), text, text.equals("keep_tab") || text.equals("keep_search") || text.startsWith("inception_"));
                    bag.updateSettings(tag -> tag.putBoolean(text, !current));
                }
                case "search" -> {
                    if (text.length() > 120 || text.codePoints().anyMatch(Character::isISOControl)) return false;
                    boolean remember = NbtAccess.getBooleanOr(effective(bag, player), "keep_search", true);
                    bag.updateSettings(tag -> { if (remember) tag.putString("last_search", text); else tag.remove("last_search"); });
                }
                case "display_slot" -> {
                    if (value < -1 || value >= bag.getContainerSize()) return false;
                    bag.updateSettings(tag -> tag.putInt("display_slot", value));
                }
                case "display_rotation" -> bag.updateSettings(tag -> tag.putInt("display_rotation", Math.floorMod(NbtAccess.getIntOr(tag, "display_rotation", 0) + 45, 360)));
                case "display_depth" -> bag.updateSettings(tag -> tag.putInt("display_depth", Math.clamp(NbtAccess.getIntOr(tag, "display_depth", 0) + Math.clamp(value, -1, 1), -16, 16)));
                case "no_sort_color" -> {
                    if (!text.matches("#?[0-9a-fA-F]{6}")) return false;
                    int color = Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
                    bag.updateSettings(tag -> tag.putInt("no_sort_color", color));
                }
                case "defaults_save" -> {
                    CompoundTag data = playerData(player);
                    data.put("defaults", SettingsTemplate.select(effective(bag, player), PREFERENCES));
                    player.setAttached(PLAYER_SETTINGS, CustomData.of(data));
                    tell(player, "Saved your backpack defaults.");
                }
                case "defaults_use" -> bag.updateSettings(tag -> PREFERENCES.forEach(tag::remove));
                case "template_save" -> {
                    if (!validName(text)) { tell(player, "Template name: 1–48 letters, numbers, spaces, _ or -."); return false; }
                    CompoundTag data = playerData(player), templates = NbtAccess.getCompoundOrEmpty(data, "templates");
                    if (!templates.contains(text) && templates.size() >= MAX_TEMPLATES) { tell(player, "At most 32 personal templates are allowed."); return false; }
                    var encoded = SettingsTemplate.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), SettingsTemplate.capture(bag)).getOrThrow();
                    if (encoded.sizeInBytes() > MAX_BYTES) { tell(player, "Template is too large."); return false; }
                    templates.put(text, encoded); data.put("templates", templates);
                    player.setAttached(PLAYER_SETTINGS, CustomData.of(data));
                    tell(player, "Saved settings template: " + text);
                }
                case "template_load" -> {
                    SettingsTemplate template = load(player, text);
                    if (template == null) { tell(player, "Settings template not found: " + text); return false; }
                    template.apply(bag);
                    tell(player, "Loaded settings template: " + text);
                }
                case "template_preview" -> {
                    SettingsTemplate template = load(player, text);
                    if (template == null) return false;
                    PREVIEWS.put(player, new Preview(bag.identity(), text, template));
                }
                case "template_delete" -> {
                    if (!validName(text)) return false;
                    CompoundTag data = playerData(player), templates = NbtAccess.getCompoundOrEmpty(data, "templates");
                    templates.remove(text); data.put("templates", templates);
                    player.setAttached(PLAYER_SETTINGS, CustomData.of(data));
                    tell(player, "Removed personal template: " + text);
                }
                case "template_export" -> {
                    if (!player.hasPermissions(2)) { tell(player, "Exporting a server data pack requires operator permission."); return false; }
                    if (!validName(text)) { tell(player, "Invalid export name."); return false; }
                    export(player, text, SettingsTemplate.capture(bag));
                    tell(player, "Exported settings data pack. Enable it with /datapack, then /reload.");
                }
                default -> { return false; }
            }
            return true;
        } catch (RuntimeException | IOException exception) {
            com.kadamitas.fabricatedbackpacks.FabricatedBackpacks.LOGGER.warn("Settings operation {} failed: {}", action, exception.getMessage());
            tell(player, "Settings operation failed; existing data was not replaced.");
            return false;
        }
    }

    private static SettingsTemplate load(ServerPlayer player, String name) throws IOException {
        var ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        if (!name.contains(":")) {
            if (!validName(name)) return null;
            CompoundTag templates = NbtAccess.getCompoundOrEmpty(playerData(player), "templates");
            return templates.contains(name) ? SettingsTemplate.CODEC.parse(ops, templates.get(name)).getOrThrow() : null;
        }
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) return null;
        var resource = player.level().getServer().getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "backpack_settings/" + id.getPath() + ".snbt"));
        if (resource.isEmpty()) return null;
        try (var stream = resource.get().open()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Template exceeds128KiB");
            return SettingsTemplate.CODEC.parse(ops, TagParser.parseTag(new String(bytes, StandardCharsets.UTF_8))).getOrThrow();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) { throw new IOException("Invalid template SNBT", exception); }
    }
    private static void export(ServerPlayer player, String name, SettingsTemplate template) throws IOException {
        Path root = player.level().getServer().getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        String slug = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        Path pack = root.resolve("fabricated_backpacks_settings_" + slug).normalize();
        if (!pack.startsWith(root) || Files.exists(pack)) throw new IOException("Export pack already exists; choose a new name");
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) if (Files.isSymbolicLink(ancestor)) throw new IOException("Symbolic-link export paths are not supported");
        String encoded = SettingsTemplate.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), template).getOrThrow().toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Template exceeds128KiB");
        Path content = pack.resolve("data/fabricated_backpacks/backpack_settings");
        Files.createDirectories(content);
        int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
        var metadata = new com.google.gson.JsonObject();
        metadata.addProperty("pack_format", format);
        metadata.addProperty("description", "Fabricated Backpacks settings template");
        var manifest = new com.google.gson.JsonObject();
        manifest.add("pack", metadata);
        Files.writeString(pack.resolve("pack.mcmeta"), manifest.toString(), StandardOpenOption.CREATE_NEW);
        Files.writeString(content.resolve(slug + ".snbt"), encoded, StandardOpenOption.CREATE_NEW);
    }
    private static void tell(ServerPlayer player, String message) { player.sendSystemMessage(Component.literal(message)); }
}
