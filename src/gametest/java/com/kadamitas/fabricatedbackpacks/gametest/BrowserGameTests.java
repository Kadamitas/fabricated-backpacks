package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogInvalidated;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogPage;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserContext;
import com.kadamitas.fabricatedbackpacks.browser.BrowserContextRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserRecipeEntry;
import com.kadamitas.fabricatedbackpacks.browser.BrowserTransferRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserTransferResult;
import com.kadamitas.fabricatedbackpacks.browser.BrowserWorkstation;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Live server-receiver tests. Embedded connections are not claims of actual client UI coverage. */
public final class BrowserGameTests {
    private static final Identifier TABLE = Identifier.withDefaultNamespace("crafting_table");
    private static final Identifier DIAMOND_BLOCK = Identifier.withDefaultNamespace("diamond_block");

    private BrowserGameTests() {}

    public static void catalogPagesAndAuthority(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            fixture.send(new BrowserContextRequest(fixture.player.containerMenu.containerId));
            fixture.send(new BrowserCatalogRequest(0, 0));
            readCatalog(helper, fixture, new CatalogAudit());
        });
    }

    private static void readCatalog(GameTestHelper helper, ClientFixture fixture, CatalogAudit audit) {
        later(helper, fixture, () -> {
            BrowserCatalogPage page = fixture.take(BrowserCatalogPage.class);
            helper.assertValueEqual(page.offset(), audit.entries.size(), "Pages arrive at the requested offset");
            if (audit.epoch == 0) {
                audit.epoch = page.epoch();
                audit.total = page.total();
                helper.assertTrue(audit.total > 71, "The catalog includes real vanilla and mod recipes");
                helper.assertFalse(fixture.take(BrowserContext.class).craftingTransfer(), "An ordinary inventory never advertises backpack crafting transfer");
            }
            helper.assertValueEqual(page.epoch(), audit.epoch, "One catalog snapshot has one epoch");
            helper.assertValueEqual(page.total(), audit.total, "Pagination retains a stable total");
            helper.assertTrue(page.entries().size() <= BrowserCatalogPage.PAGE_SIZE, "Each server page respects its entry bound");
            helper.assertFalse(page.truncated(), "The production recipe set fits the catalog budget");
            RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                BrowserCatalogPage.STREAM_CODEC.encode(encoded, page);
                helper.assertTrue(encoded.readableBytes() < 1_048_576, "The actual encoded catalog page fits the custom-payload bound");
                BrowserCatalogPage decoded = BrowserCatalogPage.STREAM_CODEC.decode(encoded);
                helper.assertValueEqual(decoded.nextOffset(), page.nextOffset(), "Native recipe-display codecs preserve page boundaries");
                helper.assertValueEqual(decoded.entries().stream().map(BrowserRecipeEntry::recipe).toList(),
                        page.entries().stream().map(BrowserRecipeEntry::recipe).toList(), "Native codecs preserve recipe identities");
            } finally { encoded.release(); }
            var manager = helper.getLevel().getServer().getRecipeManager();
            var context = SlotDisplayContext.fromLevel(helper.getLevel());
            for (BrowserRecipeEntry entry : page.entries()) {
                var holder = manager.byKey(ResourceKey.create(Registries.RECIPE, entry.recipe())).orElseThrow();
                byte[] advertised = displayBytes(helper, entry.display());
                helper.assertTrue(holder.value().display().stream().anyMatch(display -> Arrays.equals(displayBytes(helper, display), advertised)),
                        "Every advertised display encodes exactly an authoritative RecipeManager display");
                helper.assertFalse(entry.display().result().resolveForStacks(context).isEmpty(), "Each advertised static result resolves against real registries");
                helper.assertValueEqual(entry.unlocked(), holder.value().isSpecial() || fixture.player.getRecipeBook().contains(holder.id()),
                        "Unlock indicators are derived from the requesting player's recipe book");
                audit.recipeIds.add(entry.recipe());
            }
            audit.entries.addAll(page.entries());
            if (page.nextOffset() < page.total()) {
                fixture.send(new BrowserCatalogRequest(page.epoch(), page.nextOffset()));
                readCatalog(helper, fixture, audit);
                return;
            }
            long enabledDisplays = manager.getRecipes().stream().flatMap(holder -> holder.value().display().stream())
                    .filter(display -> display.isEnabled(helper.getLevel().enabledFeatures())).count();
            helper.assertValueEqual((long) audit.entries.size(), enabledDisplays, "No bounded production recipe display silently disappears");
            helper.assertTrue(audit.recipeIds.contains(Identifier.fromNamespaceAndPath("fabricated_backpacks", "backpack")), "The base backpack is discoverable");
            Identifier netheriteId = Identifier.fromNamespaceAndPath("fabricated_backpacks", "netherite_backpack");
            helper.assertTrue(audit.recipeIds.contains(netheriteId), "The direct Netherite Backpack smithing recipe is discoverable");
            BrowserRecipeEntry netherite = audit.entries.stream().filter(entry -> entry.recipe().equals(netheriteId)).findFirst().orElseThrow();
            helper.assertTrue(netherite.display() instanceof SmithingRecipeDisplay, "The indexed Netherite Backpack recipe keeps its native smithing layout");
            SmithingRecipeDisplay smithing = (SmithingRecipeDisplay) netherite.display();
            helper.assertTrue(smithing.template().resolveForStacks(context).stream().anyMatch(stack -> stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)),
                    "The browser identifies the vanilla Netherite Upgrade Smithing Template directly");
            helper.assertTrue(smithing.base().resolveForStacks(context).stream().anyMatch(stack -> stack.is(BackpackRegistry.item(BackpackTier.DIAMOND))),
                    "The browser identifies the Diamond Backpack base directly");
            helper.assertTrue(smithing.addition().resolveForStacks(context).stream().anyMatch(stack -> stack.is(Items.NETHERITE_INGOT)),
                    "The browser identifies the Netherite Ingot addition directly");
            helper.assertFalse(audit.recipeIds.contains(Identifier.fromNamespaceAndPath("fabricated_backpacks", "infinity_upgrade")), "Creative infinity has no fabricated survival recipe");
            helper.assertTrue(fixture.player.getInventory().isEmpty(), "Catalog browsing grants no inventory items");
            fixture.send(new BrowserCatalogRequest(page.epoch(), page.total()));
            later(helper, fixture, () -> {
                BrowserCatalogPage end = fixture.take(BrowserCatalogPage.class);
                helper.assertTrue(end.entries().isEmpty() && end.offset() == end.total(), "The terminal cursor is an empty completed page");
                fixture.send(new BrowserCatalogRequest(Long.MAX_VALUE, Math.min(64, audit.total)));
                later(helper, fixture, () -> {
                    BrowserCatalogPage reset = fixture.take(BrowserCatalogPage.class);
                    helper.assertValueEqual(reset.offset(), 0, "A stale epoch restarts at the first page");
                    helper.assertValueEqual(reset.epoch(), audit.epoch, "A client cannot invent a new server catalog epoch");
                    fixture.close();
                    helper.succeed();
                });
            });
        });
    }

    public static void transferPayloads(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            WorkstationMenus.PortableCrafting menu = crafting(helper, fixture.player);
            menu.backpack().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            fixture.player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 3));
            menu.grid().setItem(0, new ItemStack(Items.OAK_PLANKS));
            teach(fixture.player, TABLE);
            teach(fixture.player, DIAMOND_BLOCK);
            fixture.send(new BrowserContextRequest(menu.containerId));
            fixture.send(new BrowserCatalogRequest(0, 0));
            later(helper, fixture, () -> {
                BrowserCatalogPage page = fixture.take(BrowserCatalogPage.class);
                helper.assertTrue(fixture.take(BrowserContext.class).craftingTransfer(), "The real portable crafting menu advertises its supported transfer");
                fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, TABLE, 1));
                later(helper, fixture, () -> {
                    BrowserTransferResult result = fixture.take(BrowserTransferResult.class);
                    helper.assertTrue(result.success() && result.requestId() == 1, "A legitimate receiver request succeeds with its own correlation ID");
                    helper.assertTrue(menu.stillValid(fixture.player), "Transfer preserves the physical open backpack and its valid workstation session");
                    helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS), 4, "Transfer populates one actual crafting recipe");
                    helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS) + count(menu.backpack(), Items.OAK_PLANKS)
                            + count(fixture.player.getInventory(), Items.OAK_PLANKS), 8, "Transfer conserves all ingredients across three sources");
                    helper.assertTrue(menu.slots.getFirst().getItem().is(Items.CRAFTING_TABLE), "Vanilla recipe logic computes the output preview");
                    helper.assertValueEqual(count(fixture.player.getInventory(), Items.CRAFTING_TABLE), 0, "Transfer does not grant the preview result");
                    Snapshot before = Snapshot.of(fixture.player, menu);
                    fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, DIAMOND_BLOCK, 2));
                    fixture.send(new BrowserTransferRequest(Long.MAX_VALUE, menu.containerId, TABLE, 3));
                    fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId + 1, TABLE, 4));
                    fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, Identifier.withDefaultNamespace("not_a_real_recipe"), 5));
                    later(helper, fixture, () -> {
                        Set<Long> rejected = new HashSet<>();
                        for (int index = 0; index < 4; index++) {
                            BrowserTransferResult failure = fixture.take(BrowserTransferResult.class);
                            helper.assertFalse(failure.success(), "Missing ingredients, epochs, menu IDs and invented recipes are rejected");
                            rejected.add(failure.requestId());
                        }
                        helper.assertValueEqual(rejected, Set.of(2L, 3L, 4L, 5L), "Rejected requests preserve exact response correlation");
                        before.assertUnchanged(helper, fixture.player, menu, "Rejected transfers");
                        menu.setCarried(new ItemStack(Items.DIAMOND));
                        fixture.send(new BrowserContextRequest(menu.containerId));
                        fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, TABLE, 6));
                        later(helper, fixture, () -> {
                            helper.assertFalse(fixture.take(BrowserContext.class).craftingTransfer(), "A carried item disables transfer availability");
                            helper.assertFalse(fixture.take(BrowserTransferResult.class).success(), "The receiver independently rejects an occupied cursor");
                            helper.assertValueEqual(menu.getCarried().getCount(), 1, "Rejected transfer does not consume the cursor");
                            menu.setCarried(ItemStack.EMPTY);
                            before.assertUnchanged(helper, fixture.player, menu, "Occupied-cursor rejection");
                            int closedId = menu.containerId;
                            fixture.player.closeContainer();
                            ItemStack closedBag = fixture.player.getInventory().getItem(0).copy();
                            fixture.send(new BrowserTransferRequest(page.epoch(), closedId, TABLE, 7));
                            later(helper, fixture, () -> {
                                helper.assertFalse(fixture.take(BrowserTransferResult.class).success(), "Closed workstation IDs cannot replay transfers");
                                assertStack(helper, fixture.player.getInventory().getItem(0), closedBag, "Closed-menu replay preserves the saved backpack");
                                fixture.close();
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    public static void ghostRegistrySelection(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_PICKUP);
            fixture.player.getInventory().setItem(0, source.stack());
            BackpackMenus.openInventory(fixture.player, 0);
            BackpackMenu menu = (BackpackMenu) fixture.player.containerMenu;
            menu.clickMenuButton(fixture.player, 100);
            fixture.send(new BrowserContextRequest(menu.containerId));
            fixture.send(new MenuAction(menu.containerId, "ghost_registry", 0, 0, "minecraft:diamond"));
            later(helper, fixture, () -> {
                helper.assertFalse(fixture.take(BrowserContext.class).craftingTransfer(), "A backpack filter screen does not advertise workstation transfers");
                helper.assertTrue(menu.bag().ghost(upgrade(menu.bag(), 0), 0).is(Items.DIAMOND), "The browser's registry selection reaches the authoritative ghost receiver");
                helper.assertValueEqual(count(fixture.player.getInventory(), Items.DIAMOND), 0, "Choosing a registry filter grants no real diamond");
                helper.assertTrue(menu.getCarried().isEmpty(), "Registry selection never manufactures a cursor item");
                ItemStack before = menu.bag().stack().copy();
                fixture.send(new MenuAction(menu.containerId, "ghost_registry", -1, 0, "minecraft:gold_ingot"));
                fixture.send(new MenuAction(menu.containerId, "ghost_registry", 64, 0, "minecraft:gold_ingot"));
                fixture.send(new MenuAction(menu.containerId, "ghost_registry", 1, 0, "minecraft:missing_item"));
                fixture.send(new MenuAction(menu.containerId + 1, "ghost_registry", 0, 0, "minecraft:gold_ingot"));
                later(helper, fixture, () -> {
                    assertStack(helper, menu.bag().stack(), before, "Malformed and stale registry-filter payloads leave the backpack unchanged");
                    fixture.player.closeContainer();
                    fixture.send(new MenuAction(menu.containerId, "ghost_registry", 0, 0, "minecraft:gold_ingot"));
                    later(helper, fixture, () -> {
                        assertStack(helper, fixture.player.getInventory().getItem(0), before, "Closed filter screens cannot be replayed");
                        fixture.close();
                        helper.succeed();
                    });
                });
            });
        });
    }

    public static void limitedCraftingTransfer(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            WorkstationMenus.PortableCrafting menu = crafting(helper, fixture.player);
            menu.backpack().setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
            var recipe = helper.getLevel().getServer().getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, TABLE)).orElseThrow();
            fixture.player.resetRecipes(List.of(recipe));
            fixture.send(new BrowserCatalogRequest(0, 0));
            later(helper, fixture, () -> {
                BrowserCatalogPage page = fixture.take(BrowserCatalogPage.class);
                Snapshot before = Snapshot.of(fixture.player, menu);
                boolean prior = helper.getLevel().getGameRules().get(GameRules.LIMITED_CRAFTING);
                fixture.cleanup = () -> helper.getLevel().getGameRules().set(GameRules.LIMITED_CRAFTING, prior, helper.getLevel().getServer());
                helper.getLevel().getGameRules().set(GameRules.LIMITED_CRAFTING, true, helper.getLevel().getServer());
                fixture.send(new BrowserContextRequest(menu.containerId));
                fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, TABLE, 1));
                later(helper, fixture, () -> {
                    helper.assertTrue(fixture.take(BrowserContext.class).limitedCrafting(), "The UI receives the server's limited-crafting rule");
                    BrowserTransferResult locked = fixture.take(BrowserTransferResult.class);
                    helper.assertFalse(locked.success(), "Catalog visibility cannot bypass a locked recipe");
                    helper.assertValueEqual(locked.messageKey(), "browser.fabricated_backpacks.locked", "The failure explains the actual unlock requirement");
                    before.assertUnchanged(helper, fixture.player, menu, "Limited-crafting rejection");
                    teach(fixture.player, TABLE);
                    fixture.send(new BrowserTransferRequest(page.epoch(), menu.containerId, TABLE, 2));
                    later(helper, fixture, () -> {
                        helper.assertTrue(fixture.take(BrowserTransferResult.class).success(), "The same request is permitted after a real server recipe unlock");
                        helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS), 4, "The unlocked recipe transfers exactly its ingredients");
                        fixture.close();
                        helper.succeed();
                    });
                });
            });
        });
    }

    public static void maximumCraftingTransfers(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            fixture.send(new BrowserCatalogRequest(0, 0));
            later(helper, fixture, () -> maximumStep(helper, fixture, fixture.take(BrowserCatalogPage.class).epoch(), 0));
        });
    }

    private static void maximumStep(GameTestHelper helper, ClientFixture fixture, long epoch, int step) {
        if (step == 7) { fixture.close(); helper.succeed(); return; }
        fixture.player.closeContainer();
        fixture.player.getInventory().clearContent();
        var menu = crafting(helper, fixture.player);
        BagInventory bag = menu.backpack();
        Identifier recipe;
        if (step == 0) {
            recipe = Identifier.fromNamespaceAndPath("fabricated_backpacks_tests", "browser_ambiguous_logs");
            bag.setItem(0, new ItemStack(Items.OAK_LOG, 8));
            bag.setItem(1, new ItemStack(Items.BIRCH_LOG, 8));
        } else if (step == 1) {
            recipe = Identifier.withDefaultNamespace("oak_planks");
            ItemStack named = new ItemStack(Items.OAK_LOG, 3);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this ingredient's components"));
            bag.setItem(0, named);
            bag.setItem(1, new ItemStack(Items.OAK_LOG, 3));
        } else if (step == 2) {
            recipe = TABLE;
            for (int slot = 0; slot < 5; slot++) bag.setItem(slot, new ItemStack(Items.OAK_PLANKS, 64));
        } else if (step == 3) {
            recipe = TABLE;
            for (int slot = 0; slot < bag.getContainerSize(); slot++) bag.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            for (int slot = 1; slot < 36; slot++) fixture.player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
            for (int slot = 0; slot < 9; slot++) menu.grid().setItem(slot, new ItemStack(Items.DIRT));
        } else if (step == 4) {
            recipe = Identifier.withDefaultNamespace("cake");
            for (int slot = 0; slot < 3; slot++) bag.setItem(slot, new ItemStack(Items.MILK_BUCKET));
            bag.setItem(3, new ItemStack(Items.WHEAT, 10));
            bag.setItem(4, new ItemStack(Items.SUGAR, 10));
            bag.setItem(5, new ItemStack(Items.EGG, 4));
        } else if (step == 5) {
            recipe = TABLE;
            bag.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.FILTER)));
            bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.DIAMOND));
            bag.updateSettings(upgrade(bag, 1), state -> { state.putString("filter_mode", "ALLOW"); state.putString("filter_direction", "OUTPUT"); });
            bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        } else {
            recipe = Identifier.withDefaultNamespace("oak_planks");
            bag.upgrades().setItem(1, new ItemStack(BackpackRegistry.item(UpgradeKind.STACK_UPGRADE_TIER_1)));
            for (int slot = 0; slot < bag.getContainerSize(); slot++) bag.setItem(slot, new ItemStack(Items.COBBLESTONE, 128));
            for (int slot = 1; slot < 36; slot++) fixture.player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            bag.setItem(0, new ItemStack(Items.OAK_LOG, 128));
            ItemStack named = new ItemStack(Items.OAK_LOG, 63);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Existing variant fits only when retained"));
            menu.grid().setItem(0, named);
        }
        teach(fixture.player, recipe);
        Runnable maximum = () -> requestTransfer(helper, fixture, epoch, recipe, true, BrowserWorkstation.CRAFTING, result -> {
            helper.assertTrue(result.success(), "A maximum transfer uses owned ingredients for phase " + step);
            helper.assertTrue(menu.stillValid(fixture.player), "Maximum transfer retains the physical backpack lease");
            if (step == 0) {
                helper.assertTrue(menu.grid().getItem(0).is(Items.BIRCH_LOG) && menu.grid().getItem(1).is(Items.OAK_LOG),
                        "A broad logs ingredient yields oak to the specific oak ingredient");
                helper.assertValueEqual(menu.grid().getItem(0).getCount(), 8, "All eight complete alternative-ingredient sets fit");
                helper.assertValueEqual(count(menu.grid(), Items.OAK_LOG) + count(menu.grid(), Items.BIRCH_LOG), 16, "No alternative ingredient is lost or created");
            } else if (step == 1) {
                ItemStack input = menu.grid().getItem(0);
                helper.assertValueEqual(input.getCount(), 3, "Distinct component variants cannot form one six-item grid stack");
                helper.assertTrue(input.has(DataComponents.CUSTOM_NAME), "The selected ingredient keeps its original components");
                helper.assertValueEqual(count(menu.grid(), Items.OAK_LOG) + count(bag, Items.OAK_LOG), 6, "Both component variants remain owned");
            } else if (step == 2) {
                for (int slot : new int[] {0, 1, 3, 4}) helper.assertValueEqual(menu.grid().getItem(slot).getCount(), 64, "Maximum transfer caps each input at its normal stack limit");
                helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS) + count(bag, Items.OAK_PLANKS), 320, "The 64-set cap conserves the unused fifth stack");
                helper.assertValueEqual(count(fixture.player.getInventory(), Items.CRAFTING_TABLE), 0, "Maximum transfer never grants a result preview");
            } else if (step == 3) {
                helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS), 64, "A larger complete transfer can free the source cell needed for old inputs");
                helper.assertValueEqual(count(bag, Items.DIRT), 9, "All displaced dirt inputs return to the newly freed source cell");
                helper.assertValueEqual(count(bag, Items.COBBLESTONE), (bag.getContainerSize() - 1) * 64, "Unrelated full storage remains unchanged");
            } else if (step == 4) {
                helper.assertValueEqual(count(menu.grid(), Items.MILK_BUCKET), 3, "Unstackable ingredients bound a maximum cake transfer to one set");
                menu.clicked(0, 0, ContainerInput.PICKUP, fixture.player);
                helper.assertTrue(menu.getCarried().is(Items.CAKE), "The transferred inputs support a real vanilla result take");
                helper.assertValueEqual(count(menu.grid(), Items.BUCKET), 3, "Vanilla cake returns all three container remainders");
                helper.assertValueEqual(count(bag, Items.WHEAT), 7, "Only the three declared wheat were used");
                helper.assertValueEqual(count(bag, Items.SUGAR), 8, "Only the two declared sugar were used");
                helper.assertValueEqual(count(bag, Items.EGG), 3, "Only the one declared egg was used");
            } else if (step == 5) {
                helper.assertValueEqual(count(bag, Items.OAK_PLANKS), 4, "A rejected backpack output filter still protects all stored planks");
                helper.assertValueEqual(count(menu.grid(), Items.OAK_PLANKS), 4, "Allowed player ingredients may fill the grid independently of blocked storage");
            } else {
                helper.assertValueEqual(menu.grid().getItem(0).getCount(), 63, "Maximum transfer tries the smaller fully feasible quantity when 64 would strand old inputs");
                helper.assertTrue(menu.grid().getItem(0).has(DataComponents.CUSTOM_NAME), "The feasible maximum retains the existing component variant");
                helper.assertValueEqual(count(bag, Items.OAK_LOG), 128, "The failed 64-set attempt cannot consume any plain logs");
                helper.assertValueEqual(count(bag, Items.COBBLESTONE), (bag.getContainerSize() - 1) * 128, "Failed larger attempts leave full unrelated cells unchanged");
            }
            maximumStep(helper, fixture, epoch, step + 1);
        });
        if (step == 3) {
            Snapshot before = Snapshot.of(fixture.player, menu);
            requestTransfer(helper, fixture, epoch, recipe, false, BrowserWorkstation.CRAFTING, result -> {
                helper.assertFalse(result.success(), "A one-set transfer cannot discard obstructing old inputs when both inventories are full");
                before.assertUnchanged(helper, fixture.player, menu, "Full remainder storage");
                maximum.run();
            });
        } else if (step == 5) {
            Snapshot before = Snapshot.of(fixture.player, menu);
            requestTransfer(helper, fixture, epoch, recipe, false, BrowserWorkstation.CRAFTING, result -> {
                helper.assertFalse(result.success(), "Browser transfers cannot extract through a backpack output filter");
                before.assertUnchanged(helper, fixture.player, menu, "Output-filter rejection");
                fixture.player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 4));
                maximum.run();
            });
        } else maximum.run();
    }

    public static void portableWorkstationTransfers(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            fixture.send(new BrowserCatalogRequest(0, 0));
            later(helper, fixture, () -> portableStep(helper, fixture, fixture.take(BrowserCatalogPage.class).epoch(), 0));
        });
    }

    private static void portableStep(GameTestHelper helper, ClientFixture fixture, long epoch, int step) {
        UpgradeKind[] kinds = {UpgradeKind.STONECUTTER, UpgradeKind.SMITHING, UpgradeKind.SMELTING, UpgradeKind.AUTO_SMELTING,
                UpgradeKind.SMOKING, UpgradeKind.AUTO_SMOKING, UpgradeKind.BLASTING, UpgradeKind.AUTO_BLASTING};
        if (step == kinds.length) { fixture.close(); helper.succeed(); return; }
        fixture.player.closeContainer();
        fixture.player.getInventory().clearContent();
        UpgradeKind kind = kinds[step];
        BagInventory bag = bag(BackpackTier.NETHERITE, kind);
        var upgrade = upgrade(bag, 0);
        Container saved = bag.upgradeInventory(upgrade);
        BrowserWorkstation context = station(kind);
        Identifier recipe = stationRecipe(context);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(17);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Retained smithing base"));
        if (context == BrowserWorkstation.STONECUTTER) {
            bag.setItem(0, new ItemStack(Items.STONE, 5));
            fixture.player.getInventory().setItem(9, new ItemStack(Items.STONE, 4));
            saved.setItem(0, new ItemStack(Items.ANDESITE, 2));
        } else if (context == BrowserWorkstation.SMITHING) {
            bag.setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2));
            bag.setItem(1, sword.copy());
            fixture.player.getInventory().setItem(9, new ItemStack(Items.NETHERITE_INGOT, 2));
        } else {
            Item input = context == BrowserWorkstation.SMOKING ? Items.BEEF : Items.RAW_IRON;
            bag.setItem(0, new ItemStack(input, 5));
            fixture.player.getInventory().setItem(9, new ItemStack(input, 3));
            saved.setItem(0, new ItemStack(context == BrowserWorkstation.SMOKING ? Items.POTATO : Items.RAW_COPPER, 2));
            saved.setItem(1, new ItemStack(Items.COAL, 3));
            saved.setItem(2, new ItemStack(Items.GOLD_NUGGET, 5));
            bag.updateSettings(upgrade, state -> { state.putBoolean("enabled", false); state.putDouble("experience", 3.5); });
        }
        teach(fixture.player, recipe);
        fixture.player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(fixture.player, 0);
        BackpackMenu origin = (BackpackMenu) fixture.player.containerMenu;
        origin.clickMenuButton(fixture.player, 100);
        if (BackpackMenu.isWorkstation(kind)) WorkstationMenus.open(fixture.player, origin);
        AbstractContainerMenu menu = fixture.player.containerMenu;
        requestTransfer(helper, fixture, epoch, recipe, true, context, result -> {
            helper.assertTrue(result.success(), "The installed " + kind.id() + " accepts its own real recipe");
            helper.assertTrue(menu.stillValid(fixture.player), "Portable transfer retains its current owner and lease");
            if (context == BrowserWorkstation.STONECUTTER) {
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 9, "Stonecutting transfers all nine available input sets");
                helper.assertTrue(menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().is(Items.STONE_SLAB), "The requested stonecutting recipe is selected by identity");
                helper.assertValueEqual(menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().getCount(), 2, "The requested vanilla recipe is not confused with same-result test recipes");
                helper.assertValueEqual(count(fixture.player.getInventory(), Items.ANDESITE), 2, "Previous stonecutting inputs return intact");
            } else if (context == BrowserWorkstation.SMITHING) {
                assertStack(helper, menu.getSlot(SmithingMenu.BASE_SLOT).getItem(), sword, "Smithing transfer preserves damage and custom name");
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 1, "An unstackable base limits maximum smithing to one set");
                helper.assertTrue(menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().is(Items.NETHERITE_SWORD), "Vanilla smithing recomputes the actual result preview");
                helper.assertValueEqual(count(bag, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 1, "Unused smithing templates remain stored");
                helper.assertValueEqual(count(fixture.player.getInventory(), Items.NETHERITE_INGOT), 1, "Unused additions remain with the player");
                helper.assertValueEqual(count(fixture.player.getInventory(), Items.NETHERITE_SWORD), 0, "Transfer grants no smithing output");
            } else {
                helper.assertValueEqual(saved.getItem(0).getCount(), 8, "Cooking transfers all eight matching input sets");
                helper.assertValueEqual(saved.getItem(1).getCount(), 3, "Cooking transfer leaves actual fuel untouched");
                helper.assertTrue(saved.getItem(2).is(Items.GOLD_NUGGET) && saved.getItem(2).getCount() == 5, "Cooking transfer cannot consume or overwrite an existing result");
                helper.assertValueEqual(bag.settings(upgrade).getDoubleOr("experience", 0), 3.5, "Input transfer does not claim or invent cooking XP");
                helper.assertValueEqual(count(fixture.player.getInventory(), context == BrowserWorkstation.SMOKING ? Items.POTATO : Items.RAW_COPPER), 2,
                        "Previous cooking inputs return intact");
            }
            List<ItemStack> inputsBefore = snapshot(saved);
            fixture.player.getInventory().setItem(10, new ItemStack(Items.OAK_PLANKS, 4));
            teach(fixture.player, TABLE);
            requestTransfer(helper, fixture, epoch, TABLE, true, context, incompatible -> {
                helper.assertFalse(incompatible.success(), "An incompatible recipe cannot change the current station or open another one");
                helper.assertTrue(fixture.player.containerMenu == menu, "Rejected transfer never switches workstations");
                for (int slot = 0; slot < inputsBefore.size(); slot++) assertStack(helper, saved.getItem(slot), inputsBefore.get(slot), "Incompatible transfer preserves slot " + slot);
                helper.assertValueEqual(fixture.player.getInventory().getItem(10).getCount(), 4, "Incompatible recipe leaves even available ingredients alone");
                fixture.player.closeContainer();
                BagInventory reloaded = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
                Container restored = reloaded.upgradeInventory(upgrade(reloaded, 0));
                for (int slot = 0; slot < inputsBefore.size(); slot++) assertStack(helper, restored.getItem(slot), inputsBefore.get(slot), "Transferred persistent input survives codec round trip at slot " + slot);
                portableStep(helper, fixture, epoch, step + 1);
            });
        });
    }

    public static void nativeWorkstationTransfers(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            fixture.send(new BrowserCatalogRequest(0, 0));
            later(helper, fixture, () -> nativeStep(helper, fixture, fixture.take(BrowserCatalogPage.class).epoch(), 0));
        });
    }

    private static void nativeStep(GameTestHelper helper, ClientFixture fixture, long epoch, int step) {
        var blocks = List.of(Blocks.CRAFTING_TABLE, Blocks.STONECUTTER, Blocks.SMITHING_TABLE, Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE);
        BrowserWorkstation[] contexts = {BrowserWorkstation.CRAFTING, BrowserWorkstation.STONECUTTER, BrowserWorkstation.SMITHING,
                BrowserWorkstation.SMELTING, BrowserWorkstation.SMOKING, BrowserWorkstation.BLASTING};
        if (step == blocks.size()) { fixture.close(); helper.succeed(); return; }
        fixture.player.closeContainer();
        fixture.player.getInventory().clearContent();
        BlockPos local = new BlockPos(2 + step % 3, 1, 2 + step / 3);
        helper.setBlock(local, blocks.get(step));
        BlockPos position = helper.absolutePos(local);
        var provider = helper.getLevel().getBlockState(position).getMenuProvider(helper.getLevel(), position);
        helper.assertTrue(provider != null && fixture.player.openMenu(provider).isPresent(), "An actual placed vanilla workstation opens its own menu");
        AbstractContainerMenu menu = fixture.player.containerMenu;
        BrowserWorkstation context = contexts[step];
        Identifier recipe = context == BrowserWorkstation.CRAFTING ? TABLE : stationRecipe(context);
        if (context == BrowserWorkstation.CRAFTING) fixture.player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 8));
        else if (context == BrowserWorkstation.STONECUTTER) fixture.player.getInventory().setItem(9, new ItemStack(Items.STONE, 8));
        else if (context == BrowserWorkstation.SMITHING) {
            fixture.player.getInventory().setItem(9, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2));
            fixture.player.getInventory().setItem(10, new ItemStack(Items.DIAMOND_SWORD));
            fixture.player.getInventory().setItem(11, new ItemStack(Items.NETHERITE_INGOT, 2));
        } else {
            fixture.player.getInventory().setItem(9, new ItemStack(context == BrowserWorkstation.SMOKING ? Items.BEEF : Items.RAW_IRON, 8));
            menu.getSlot(1).set(new ItemStack(Items.COAL, 3));
            menu.getSlot(2).set(new ItemStack(Items.GOLD_NUGGET, 64));
        }
        teach(fixture.player, recipe);
        requestTransfer(helper, fixture, epoch, recipe, true, context, result -> {
            helper.assertTrue(result.success(), "The real placed " + context + " menu accepts a compatible browser transfer");
            helper.assertTrue(menu.stillValid(fixture.player), "Native transfer preserves block access validity");
            if (context == BrowserWorkstation.CRAFTING) {
                for (int slot : new int[] {1, 2, 4, 5}) helper.assertValueEqual(menu.getSlot(slot).getItem().getCount(), 2, "Native crafting receives two complete recipe sets");
                helper.assertTrue(menu.getSlot(0).getItem().is(Items.CRAFTING_TABLE), "The native crafting output uses vanilla recipe placement");
            } else if (context == BrowserWorkstation.STONECUTTER) {
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 8, "Native stonecutting receives its input count");
                helper.assertTrue(menu.getSlot(1).getItem().is(Items.STONE_SLAB) && menu.getSlot(1).getItem().getCount() == 2,
                        "Native stonecutting selects the exact requested recipe instead of a display index");
            } else if (context == BrowserWorkstation.SMITHING) {
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 1, "Native smithing respects its nonstacking base");
                helper.assertTrue(menu.getSlot(3).getItem().is(Items.NETHERITE_SWORD), "Native smithing owns the actual preview");
            } else {
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 8, "Native cooking receives matching input only");
                helper.assertValueEqual(menu.getSlot(1).getItem().getCount(), 3, "Blocked native output prevents any vanilla fuel use during the assertion window");
                helper.assertTrue(menu.getSlot(2).getItem().is(Items.GOLD_NUGGET) && menu.getSlot(2).getItem().getCount() == 64,
                        "Native result storage is never rewritten by browser transfer");
            }
            Runnable closeAndReplay = () -> {
                int oldMenu = fixture.player.containerMenu.containerId;
                fixture.player.closeContainer();
                List<ItemStack> before = snapshot(fixture.player.getInventory());
                long correlation = fixture.nextRequest++;
                fixture.send(new BrowserTransferRequest(epoch, oldMenu, recipe, correlation, true));
                later(helper, fixture, () -> {
                    helper.assertFalse(fixture.take(BrowserTransferResult.class).success(), "A closed native menu rejects maximum-transfer replay");
                    for (int slot = 0; slot < before.size(); slot++) assertStack(helper, fixture.player.getInventory().getItem(slot), before.get(slot), "Closed native transfer preserves player slot " + slot);
                    nativeStep(helper, fixture, epoch, step + 1);
                });
            };
            if (context == BrowserWorkstation.SMELTING) {
                fixture.player.setGameMode(GameType.SPECTATOR);
                if (fixture.player.containerMenu != menu) fixture.player.openMenu(provider);
                AbstractContainerMenu spectatorMenu = fixture.player.containerMenu;
                helper.assertTrue(spectatorMenu instanceof net.minecraft.world.inventory.FurnaceMenu && spectatorMenu.stillValid(fixture.player),
                        "The spectator holds a real readable native furnace menu");
                List<ItemStack> beforeInventory = snapshot(fixture.player.getInventory());
                List<ItemStack> beforeFurnace = spectatorMenu.slots.subList(0, 3).stream().map(slot -> slot.getItem().copy()).toList();
                helper.assertFalse(WorkstationMenus.transfer(fixture.player, recipe, true), "The common transfer entry point independently rejects spectators");
                requestTransfer(helper, fixture, epoch, recipe, false, BrowserWorkstation.NONE, rejected -> {
                    helper.assertFalse(rejected.success(), "A spectator cannot move furnace inputs into its inventory through a browser packet");
                    for (int slot = 0; slot < beforeInventory.size(); slot++) assertStack(helper, fixture.player.getInventory().getItem(slot), beforeInventory.get(slot), "Spectator rejection preserves inventory slot " + slot);
                    for (int slot = 0; slot < 3; slot++) assertStack(helper, spectatorMenu.getSlot(slot).getItem(), beforeFurnace.get(slot), "Spectator rejection preserves physical furnace slot " + slot);
                    fixture.player.setGameMode(GameType.SURVIVAL);
                    closeAndReplay.run();
                });
            } else closeAndReplay.run();
        });
    }

    public static void stonecutterReloadTransfers(GameTestHelper helper) {
        ClientFixture fixture = client(helper);
        ready(helper, fixture, () -> {
            BlockPos local = new BlockPos(2, 1, 2);
            helper.setBlock(local, Blocks.STONECUTTER);
            BlockPos position = helper.absolutePos(local);
            fixture.player.openMenu(helper.getLevel().getBlockState(position).getMenuProvider(helper.getLevel(), position));
            StonecutterMenu menu = (StonecutterMenu) fixture.player.containerMenu;
            Identifier recipeId = stationRecipe(BrowserWorkstation.STONECUTTER);
            teach(fixture.player, recipeId);
            menu.getSlot(0).set(new ItemStack(Items.STONE, 8));
            for (int index = 0; index < menu.getNumberOfVisibleRecipes(); index++) {
                if (menu.getVisibleRecipes().entries().get(index).recipe().recipe()
                        .map(recipe -> recipe.id().identifier().equals(recipeId)).orElse(false)) menu.clickMenuButton(fixture.player, index);
            }
            helper.assertTrue(menu.getSlot(1).getItem().is(Items.STONE_SLAB), "The live native menu first caches the original recipe");
            RecipeManager manager = helper.getLevel().getServer().getRecipeManager();
            List<RecipeHolder<?>> originals = new ArrayList<>(manager.getRecipes());
            RecipeHolder<?> original = manager.byKey(ResourceKey.create(Registries.RECIPE, recipeId)).orElseThrow();
            var replacement = new RecipeHolder<>(original.id(), new StonecutterRecipe(new Recipe.CommonInfo(false),
                    ((StonecutterRecipe) original.value()).input(), new ItemStackTemplate(Items.COPPER_INGOT, 3)));
            List<RecipeHolder<?>> changed = new ArrayList<>(originals);
            changed.set(changed.indexOf(original), replacement);
            // Exercise vanilla's actual apply/finalize reload phase without yielding. Restoring the identical
            // original holders in finally prevents this focused cache fixture from invalidating other live tests.
            try {
                applyRecipes(helper, manager, RecipeMap.create(changed));
                helper.assertTrue(menu.getSlot(1).getItem().is(Items.STONE_SLAB), "Same input keeps vanilla's old preview until the cache is refreshed");
                helper.assertTrue(WorkstationMenus.transfer(fixture.player, recipeId, true), "A current recipe transfer refreshes a same-item native stonecutter cache");
                helper.assertTrue(menu.getSlot(1).getItem().is(Items.COPPER_INGOT) && menu.getSlot(1).getItem().getCount() == 3,
                        "The refreshed result comes from the replacement holder, not the stale output");
                helper.assertTrue(menu.getVisibleRecipes().entries().get(menu.getSelectedRecipeIndex()).recipe().recipe()
                        .map(recipe -> recipe.value() == replacement.value()).orElse(false), "The selected cache entry is the current server recipe object");
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 8, "Refreshing a same-count transfer does not consume its inputs");
                menu.clicked(1, 0, ContainerInput.PICKUP, fixture.player);
                helper.assertTrue(menu.getCarried().is(Items.COPPER_INGOT) && menu.getCarried().getCount() == 3, "A real result take uses the updated output");
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 7, "The updated recipe consumes one owned input");
                ItemStack produced = menu.getCarried();
                menu.setCarried(ItemStack.EMPTY);
                fixture.player.getInventory().placeItemBackInInventory(produced);
                List<ItemStack> before = snapshot(fixture.player.getInventory());
                changed.remove(replacement);
                applyRecipes(helper, manager, RecipeMap.create(changed));
                helper.assertFalse(WorkstationMenus.transfer(fixture.player, recipeId, true), "A recipe removed by the apply phase cannot transfer from an old cache");
                helper.assertValueEqual(menu.getSlot(0).getItem().getCount(), 7, "Removed-recipe rejection preserves the current input");
                for (int slot = 0; slot < before.size(); slot++) assertStack(helper, fixture.player.getInventory().getItem(slot), before.get(slot), "Removed recipe preserves player slot " + slot);
            } finally {
                applyRecipes(helper, manager, RecipeMap.create(originals));
                fixture.close();
            }
            helper.succeed();
        });
    }

    private static void applyRecipes(GameTestHelper helper, RecipeManager manager, RecipeMap recipes) {
        try {
            var apply = RecipeManager.class.getDeclaredMethod("apply", RecipeMap.class,
                    net.minecraft.server.packs.resources.ResourceManager.class, net.minecraft.util.profiling.ProfilerFiller.class);
            apply.setAccessible(true);
            apply.invoke(manager, recipes, helper.getLevel().getServer().getResourceManager(), net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
            manager.finalizeRecipeLoading(helper.getLevel().enabledFeatures());
        } catch (ReflectiveOperationException invalidFixture) {
            throw new AssertionError("Cannot exercise the actual vanilla recipe apply phase", invalidFixture);
        }
    }

    private static BrowserWorkstation station(UpgradeKind kind) {
        return switch (kind) {
            case STONECUTTER -> BrowserWorkstation.STONECUTTER;
            case SMITHING -> BrowserWorkstation.SMITHING;
            case SMOKING, AUTO_SMOKING -> BrowserWorkstation.SMOKING;
            case BLASTING, AUTO_BLASTING -> BrowserWorkstation.BLASTING;
            default -> BrowserWorkstation.SMELTING;
        };
    }

    private static Identifier stationRecipe(BrowserWorkstation context) {
        return Identifier.withDefaultNamespace(switch (context) {
            case STONECUTTER -> "stone_slab_from_stone_stonecutting";
            case SMITHING -> "netherite_sword_smithing";
            case SMOKING -> "cooked_beef_from_smoking";
            case BLASTING -> "iron_ingot_from_blasting_raw_iron";
            case SMELTING -> "iron_ingot_from_smelting_raw_iron";
            default -> throw new IllegalArgumentException("Not a tested station");
        });
    }

    private static void requestTransfer(GameTestHelper helper, ClientFixture fixture, long epoch, Identifier recipe, boolean maximum,
                                        BrowserWorkstation expected, Consumer<BrowserTransferResult> next) {
        int menu = fixture.player.containerMenu.containerId;
        long correlation = fixture.nextRequest++;
        fixture.send(new BrowserContextRequest(menu));
        fixture.send(new BrowserTransferRequest(epoch, menu, recipe, correlation, maximum));
        later(helper, fixture, () -> {
            helper.assertValueEqual(fixture.take(BrowserContext.class).workstation(), expected, "The context names the actual open workstation type");
            BrowserTransferResult result = fixture.take(BrowserTransferResult.class);
            helper.assertValueEqual(result.requestId(), correlation, "Every transfer response belongs to its exact request");
            next.accept(result);
        });
    }

    private static WorkstationMenus.PortableCrafting crafting(GameTestHelper helper, ServerPlayer player) {
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        player.getInventory().setItem(0, source.stack());
        BackpackMenus.openInventory(player, 0);
        BackpackMenu origin = (BackpackMenu) player.containerMenu;
        origin.clickMenuButton(player, 100);
        WorkstationMenus.open(player, origin);
        helper.assertTrue(player.containerMenu instanceof WorkstationMenus.PortableCrafting, "A real installed crafting upgrade opens its native workstation");
        return (WorkstationMenus.PortableCrafting) player.containerMenu;
    }

    private static void teach(ServerPlayer player, Identifier recipe) {
        player.awardRecipesByKey(List.of(ResourceKey.create(Registries.RECIPE, recipe)));
    }

    private static ClientFixture client(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "fb_browse_" + id.toString().substring(0, 6)), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        ClientFixture fixture = new ClientFixture(player, connection, channel);
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof ClientboundCustomPayloadPacket packet) fixture.inbox.add(packet.payload());
                super.write(context, message, promise);
            }
        });
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setPos(helper.absoluteVec(new Vec3(3.5, 1, 3.5)));
        return fixture;
    }

    private static void ready(GameTestHelper helper, ClientFixture fixture, Runnable action) {
        // This is the actual Fabric channel-registration payload, only in the test mod.
        fixture.send(new RegistrationPayload(RegistrationPayload.REGISTER, List.of(BrowserCatalogPage.TYPE.id(), BrowserContext.TYPE.id(),
                BrowserCatalogInvalidated.TYPE.id(), BrowserTransferResult.TYPE.id())));
        later(helper, fixture, () -> {
            helper.assertTrue(ServerPlayNetworking.canSend(fixture.player, BrowserCatalogPage.TYPE), "The fixture advertises real browser payload channels");
            fixture.inbox.clear();
            action.run();
        });
    }

    private static void later(GameTestHelper helper, ClientFixture fixture, Runnable action) {
        helper.runAfterDelay(2, () -> {
            try {
                fixture.channel.runPendingTasks();
                action.run();
            } catch (RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        });
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
        return result;
    }

    private static byte[] displayBytes(GameTestHelper helper, RecipeDisplay display) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            RecipeDisplay.STREAM_CODEC.encode(buffer, display);
            return ByteBufUtil.getBytes(buffer);
        } finally { buffer.release(); }
    }

    private record Snapshot(ItemStack bag, List<ItemStack> inventory, List<ItemStack> grid) {
        static Snapshot of(ServerPlayer player, WorkstationMenus.PortableCrafting menu) {
            return new Snapshot(menu.backpack().stack().copy(), snapshot(player.getInventory()), snapshot(menu.grid()));
        }
        void assertUnchanged(GameTestHelper helper, ServerPlayer player, WorkstationMenus.PortableCrafting menu, String reason) {
            assertStack(helper, menu.backpack().stack(), bag, reason + " preserve every backpack component");
            for (int index = 0; index < inventory.size(); index++) assertStack(helper, player.getInventory().getItem(index), inventory.get(index), reason + " preserve inventory slot " + index);
            for (int index = 0; index < grid.size(); index++) assertStack(helper, menu.grid().getItem(index), grid.get(index), reason + " preserve crafting slot " + index);
        }
    }

    private static final class CatalogAudit {
        long epoch;
        int total;
        final List<BrowserRecipeEntry> entries = new ArrayList<>();
        final Set<Identifier> recipeIds = new HashSet<>();
    }

    private static final class ClientFixture implements AutoCloseable {
        final ServerPlayer player;
        final Connection connection;
        final EmbeddedChannel channel;
        final List<CustomPacketPayload> inbox = new ArrayList<>();
        Runnable cleanup = () -> {};
        long nextRequest = 100;
        boolean closed;
        ClientFixture(ServerPlayer player, Connection connection, EmbeddedChannel channel) {
            this.player = player;
            this.connection = connection;
            this.channel = channel;
        }
        void send(CustomPacketPayload payload) { player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload)); }
        <T extends CustomPacketPayload> T take(Class<T> type) {
            channel.runPendingTasks();
            for (int index = 0; index < inbox.size(); index++) if (type.isInstance(inbox.get(index))) return type.cast(inbox.remove(index));
            throw new AssertionError("The actual server did not send " + type.getSimpleName());
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            cleanup.run();
            player.closeContainer();
            connection.disconnect(Component.literal("Browser test finished"));
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
        }
    }
}
