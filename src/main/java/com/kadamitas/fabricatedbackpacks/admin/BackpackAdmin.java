package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Administrator-only discovery, recovery and authoring; no client packet grants inventory. */
public final class BackpackAdmin {
    private static final Map<ServerPlayer, DynamicBackpackBuilder> DRAFTS = new WeakHashMap<>();
    private static final SimpleCommandExceptionType DENIED = new SimpleCommandExceptionType(Component.literal("Game-master permission is required"));
    private static final int PAGE_SIZE = 20;
    private BackpackAdmin() { }

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            dispatcher.register(root("fb", context));
            dispatcher.register(root("fabricatedbackpacks", context));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DRAFTS.keySet().removeIf(player -> player.level().getServer() == server));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, CommandBuildContext context) {
        return literal(name).requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(command -> guarded(command, () -> tell(command.getSource(),
                        "Fabricated Backpacks: list, recover, cleanup nonplayer [empty], template, dynamic", 1)))
                .then(literal("list").executes(command -> list(command, "", 1))
                        .then(literal("page").then(argument("page", IntegerArgumentType.integer(1)).executes(command -> list(command, "", IntegerArgumentType.getInteger(command, "page")))))
                        .then(literal("player").then(argument("player", StringArgumentType.word()).executes(command -> list(command, StringArgumentType.getString(command, "player"), 1))
                                .then(argument("page", IntegerArgumentType.integer(1)).executes(command -> list(command, StringArgumentType.getString(command, "player"), IntegerArgumentType.getInteger(command, "page")))))))
                .then(recovery("recover"))
                .then(recovery("give"))
                .then(literal("cleanup").then(literal("nonplayer").executes(command -> cleanup(command, false))
                        .then(literal("empty").executes(command -> cleanup(command, true)))))
                .then(templates())
                .then(dynamic(context));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> recovery(String name) {
        return literal(name).then(argument("identity", UuidArgument.uuid()).then(argument("players", EntityArgument.players())
                .executes(command -> guarded(command, () -> {
                    var archive = data(command).archive(UuidArgument.getUuid(command, "identity").toString())
                            .orElseThrow(() -> new IllegalArgumentException("No archived backpack with that UUID"));
                    int count = AdminDelivery.give(archive.backpack(), EntityArgument.getPlayers(command, "players"));
                    return tell(command.getSource(), "Recovered " + count + " independent backpack copies; the archive is unchanged", count);
                }))));
    }
    private static int list(CommandContext<CommandSourceStack> command, String owner, int page) throws CommandSyntaxException {
        return guarded(command, () -> {
            var entries = data(command).archives(owner);
            long from = (long)(page - 1) * PAGE_SIZE;
            int pages = Math.max(1, Math.ceilDiv(entries.size(), PAGE_SIZE));
            tell(command.getSource(), entries.size() + " archived backpacks; page " + page + "/" + pages, entries.size());
            entries.stream().skip(from).limit(PAGE_SIZE).forEach(entry -> command.getSource().sendSuccess(() -> Component.literal(
                    entry.identity() + " | " + entry.itemName() + " | " + (entry.playerBacked() ? "last player: " + entry.ownerName() + " (" + entry.ownerId() + ")" : "no player access")
                            + " | " + Instant.ofEpochMilli(entry.accessedAt())), false));
            return entries.size();
        });
    }
    private static int cleanup(CommandContext<CommandSourceStack> command, boolean onlyEmpty) throws CommandSyntaxException {
        return guarded(command, () -> {
            int count = data(command).cleanupNonPlayer(onlyEmpty);
            return tell(command.getSource(), "Removed " + count + " non-player archives" + (onlyEmpty ? " with empty storage" : "") + "; player archives were preserved", count);
        });
    }
    private static LiteralArgumentBuilder<CommandSourceStack> templates() {
        return literal("template")
                .then(literal("list").executes(command -> guarded(command, () -> {
                    var names = BackpackTemplates.names(command.getSource().getServer());
                    return tell(command.getSource(), "Whole-backpack templates (plain names: local; namespaced: datapack): " + String.join(", ", names), names.size());
                })))
                .then(literal("create").then(localName().executes(command -> create(command, false))
                        .then(literal("overwrite").executes(command -> create(command, true)))))
                .then(literal("delete").then(localName().executes(command -> guarded(command, () -> {
                    String name = StringArgumentType.getString(command, "name");
                    if (!data(command).deleteTemplate(name)) throw new IllegalArgumentException("Local template not found");
                    return tell(command.getSource(), "Deleted local template " + name, 1);
                }))))
                .then(literal("give").then(templateReference().then(argument("players", EntityArgument.players())
                        .executes(command -> guarded(command, () -> {
                            var template = load(command);
                            int count = AdminDelivery.give(template.backpack(), EntityArgument.getPlayers(command, "players"));
                            return tell(command.getSource(), "Gave " + count + " independent template copies", count);
                        })))))
                .then(literal("export").then(templateReference().executes(command -> export(command, reference(command)))
                        .then(argument("export_name", StringArgumentType.word()).executes(command -> export(command, StringArgumentType.getString(command, "export_name"))))));
    }
    private static int create(CommandContext<CommandSourceStack> command, boolean overwrite) throws CommandSyntaxException {
        return guarded(command, () -> {
            ServerPlayer player = command.getSource().getPlayerOrException();
            WholeBagTemplate.capture(player.getMainHandItem());
            BagInventory bag = BagInventory.of(player.getMainHandItem());
            WholeBagTemplate template = WholeBagTemplate.capture(bag.stack());
            BackpackTemplates.encode(player.registryAccess(), template);
            String name = StringArgumentType.getString(command, "name");
            data(command).putTemplate(name, template.backpack(), overwrite);
            BackpackArchives.record(player.level(), bag, player);
            return tell(command.getSource(), "Saved local whole-backpack template " + name, 1);
        });
    }
    private static int export(CommandContext<CommandSourceStack> command, String name) throws CommandSyntaxException {
        return guarded(command, () -> {
            var path = BackpackTemplates.export(command.getSource().getServer(), reference(command), name);
            return tell(command.getSource(), "Created datapack " + path.getFileName() + "; enable it with /datapack, then /reload", 1);
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dynamic(CommandBuildContext context) {
        var item = argument("item", ItemArgument.item(context));
        item.executes(command -> addItem(command, 1, DynamicBackpackBuilder.AUTO))
                .then(argument("count", IntegerArgumentType.integer(1)).executes(command -> addItem(command, IntegerArgumentType.getInteger(command, "count"), DynamicBackpackBuilder.AUTO))
                        .then(argument("slot", IntegerArgumentType.integer(0)).executes(command -> addItem(command, IntegerArgumentType.getInteger(command, "count"), IntegerArgumentType.getInteger(command, "slot")))));
        return literal("dynamic")
                .then(literal("start").then(argument("tier", StringArgumentType.word())
                        .suggests((command, suggestions) -> SharedSuggestionProvider.suggest(Arrays.stream(BackpackTier.values()).map(BackpackTier::id), suggestions))
                        .executes(command -> guarded(command, () -> {
                            String name = StringArgumentType.getString(command, "tier");
                            BackpackTier tier = BackpackTier.byId(name).orElseGet(() -> Arrays.stream(BackpackTier.values())
                                    .filter(value -> value.name().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown backpack tier")));
                            return begin(command, new ItemStack(BackpackRegistry.item(tier)));
                        }))))
                .then(literal("base").then(templateReference().executes(command -> guarded(command, () -> begin(command, load(command).backpack())))))
                .then(literal("item").then(item))
                .then(literal("upgrade").then(argument("upgrade", ItemArgument.item(context)).executes(command -> guarded(command, () -> {
                    ServerPlayer author = command.getSource().getPlayerOrException();
                    int slot = draft(author).addUpgrade(ItemArgument.getItem(command, "upgrade").createItemStack(1), author);
                    return tell(command.getSource(), "Added upgrade in slot " + slot, 1);
                }))))
                .then(literal("preview").executes(command -> guarded(command, () -> {
                    ServerPlayer author = command.getSource().getPlayerOrException();
                    var result = draft(author).build(author);
                    reportLeftovers(command.getSource(), result);
                    return result.leftovers().isEmpty() ? 1 : 0;
                })))
                .then(literal("end").then(localName().executes(command -> end(command, false))
                        .then(literal("overwrite").executes(command -> end(command, true)))))
                .then(literal("cancel").executes(command -> guarded(command, () -> {
                    ServerPlayer author = command.getSource().getPlayerOrException();
                    if (DRAFTS.remove(author) == null) throw new IllegalArgumentException("No dynamic draft is open");
                    return tell(command.getSource(), "Discarded the virtual draft; no live inventory was changed", 1);
                })));
    }
    private static int begin(CommandContext<CommandSourceStack> command, ItemStack base) throws CommandSyntaxException {
        ServerPlayer author = command.getSource().getPlayerOrException();
        if (DRAFTS.containsKey(author)) throw new IllegalArgumentException("A draft is already open; end or cancel it first");
        DRAFTS.put(author, new DynamicBackpackBuilder(base));
        return tell(command.getSource(), "Started dynamic draft; item slot numbers begin at zero", 1);
    }
    private static int addItem(CommandContext<CommandSourceStack> command, int count, int slot) throws CommandSyntaxException {
        return guarded(command, () -> {
            ServerPlayer author = command.getSource().getPlayerOrException();
            ItemStack stack = ItemArgument.getItem(command, "item").createItemStack(1);
            stack.setCount(count); // The draft validates the enhanced capacity after its upgrades are chosen.
            draft(author).addItem(stack, slot);
            return tell(command.getSource(), "Queued " + count + " " + stack.getHoverName().getString() + (slot == DynamicBackpackBuilder.AUTO ? " for automatic placement" : " for slot " + slot), count);
        });
    }
    private static int end(CommandContext<CommandSourceStack> command, boolean overwrite) throws CommandSyntaxException {
        return guarded(command, () -> {
            ServerPlayer author = command.getSource().getPlayerOrException();
            var result = draft(author).build(author);
            if (!result.leftovers().isEmpty()) { reportLeftovers(command.getSource(), result); return 0; }
            BackpackTemplates.encode(author.registryAccess(), WholeBagTemplate.capture(result.backpack()));
            String name = StringArgumentType.getString(command, "name");
            data(command).putTemplate(name, result.backpack(), overwrite);
            DRAFTS.remove(author);
            return tell(command.getSource(), "Saved complete dynamic template " + name, 1);
        });
    }
    private static void reportLeftovers(CommandSourceStack source, DynamicBackpackBuilder.Result result) {
        if (result.leftovers().isEmpty()) { tell(source, "The draft fits; no item requests remain", 1); return; }
        source.sendFailure(Component.literal("Draft retained: " + result.leftoverCount() + " items do not fit. Add capacity or cancel; no template was saved."));
        result.leftovers().stream().limit(10).forEach(value -> source.sendFailure(Component.literal(value.stack().getCount() + " "
                + value.stack().getHoverName().getString() + (value.slot() == DynamicBackpackBuilder.AUTO ? " (automatic placement)" : " (slot " + value.slot() + ")"))));
    }
    public static Optional<DynamicBackpackBuilder> building(ServerPlayer author) { return Optional.ofNullable(DRAFTS.get(author)); }
    private static DynamicBackpackBuilder draft(ServerPlayer author) {
        return building(author).orElseThrow(() -> new IllegalArgumentException("Start a dynamic draft first"));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> localName() { return argument("name", StringArgumentType.word()); }
    private static RequiredArgumentBuilder<CommandSourceStack, net.minecraft.resources.Identifier> templateReference() {
        return argument("template", IdentifierArgument.id()).suggests((command, suggestions) ->
                SharedSuggestionProvider.suggest(BackpackTemplates.names(command.getSource().getServer()), suggestions));
    }
    private static String reference(CommandContext<CommandSourceStack> command) {
        // Vanilla's identifier argument supports ':' on clients. Keep the user's explicit namespace
        // so an unqualified local name never masks an explicitly namespaced datapack template.
        return command.getNodes().stream().filter(node -> node.getNode().getName().equals("template"))
                .reduce((first, last) -> last).orElseThrow().getRange().get(command.getInput());
    }
    private static WholeBagTemplate load(CommandContext<CommandSourceStack> command) throws IOException {
        return BackpackTemplates.load(command.getSource().getServer(), reference(command))
                .orElseThrow(() -> new IllegalArgumentException("Whole-backpack template not found"));
    }
    private static AdminSavedData data(CommandContext<CommandSourceStack> command) { return AdminSavedData.of(command.getSource().getServer()); }
    private static int tell(CommandSourceStack source, String message, int result) { source.sendSuccess(() -> Component.literal(message), false); return result; }
    @FunctionalInterface private interface CommandAction { int run() throws CommandSyntaxException, IOException; }
    private static int guarded(CommandContext<CommandSourceStack> command, CommandAction action) throws CommandSyntaxException {
        if (!command.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) throw DENIED.create();
        try { return action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            command.getSource().sendFailure(Component.literal(exception.getMessage() == null ? "Administration operation failed" : exception.getMessage()));
            return 0;
        }
    }
}
