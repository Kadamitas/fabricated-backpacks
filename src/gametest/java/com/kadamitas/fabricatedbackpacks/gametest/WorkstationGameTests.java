package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.menu.BackpackSessionMenu;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationHistory;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.upgrade.InventoryMoves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class WorkstationGameTests {
    private WorkstationGameTests() {}
    private static RecipeSerializer<ChoiceRecipe> firstChoice;
    private static RecipeSerializer<ChoiceRecipe> secondChoice;
    static void registerFixtures() {
        ChoiceRecipe first = new ChoiceRecipe(false), second = new ChoiceRecipe(true);
        firstChoice = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "choice_a"),
                choiceSerializer(first));
        secondChoice = Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "choice_b"),
                choiceSerializer(second));
    }
    private static RecipeSerializer<ChoiceRecipe> choiceSerializer(ChoiceRecipe recipe) {
        return new RecipeSerializer<>() {
            @Override public MapCodec<ChoiceRecipe> codec() { return MapCodec.unit(recipe); }
            @Override public StreamCodec<RegistryFriendlyByteBuf, ChoiceRecipe> streamCodec() { return StreamCodec.unit(recipe); }
        };
    }
    private record ChoiceRecipe(boolean second) implements CraftingRecipe {
        @Override public boolean matches(CraftingInput input, Level level) { return input.size() == 1 && input.getItem(0).is(Items.NAUTILUS_SHELL); }
        @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) { return getResultItem(registries); }
        @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return new ItemStack(second ? Items.COPPER_INGOT : Items.AMETHYST_SHARD); }
        @Override public boolean canCraftInDimensions(int width, int height) { return width >= 1 && height >= 1; }
        @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
            return NonNullList.withSize(input.size(), new ItemStack(second ? Items.IRON_NUGGET : Items.GOLD_NUGGET));
        }
        @Override public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
        @Override public String getGroup() { return ""; }
        @Override public boolean isSpecial() { return false; }
        @Override public boolean showNotification() { return false; }
        @Override public NonNullList<Ingredient> getIngredients() { return NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.NAUTILUS_SHELL)); }
        @Override public RecipeSerializer<ChoiceRecipe> getSerializer() { return second ? secondChoice : firstChoice; }
    }

    static AbstractContainerMenu open(ServerPlayer player, BagInventory bag) {
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        var origin = (BackpackMenu) player.containerMenu;
        origin.clickMenuButton(player, 100);
        WorkstationMenus.open(player, origin);
        return player.containerMenu;
    }

    static void teach(ServerPlayer player, String recipe) {
        player.awardRecipesByKey(List.of(ResourceLocation.parse(recipe)));
    }

    static void craftingRemaindersAndPersistence(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        teach(player, "minecraft:cake");
        var menu = (WorkstationMenus.PortableCrafting) open(player, bag);
        Container grid = menu.grid();
        for (int slot = 0; slot < 3; slot++) grid.setItem(slot, new ItemStack(Items.MILK_BUCKET));
        grid.setItem(3, new ItemStack(Items.SUGAR));
        grid.setItem(4, new ItemStack(Items.EGG));
        grid.setItem(5, new ItemStack(Items.SUGAR));
        for (int slot = 6; slot < 9; slot++) grid.setItem(slot, new ItemStack(Items.WHEAT));
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.CAKE), "Vanilla recipes compute the portable crafting output");
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.CAKE) && menu.getCarried().getCount() == 1, "Taking the real result creates one cake");
        helper.assertValueEqual(count(grid, Items.BUCKET), 3, "All three milk bucket crafting remainders remain owned");
        helper.assertValueEqual(count(grid, Items.MILK_BUCKET) + count(grid, Items.SUGAR) + count(grid, Items.EGG) + count(grid, Items.WHEAT), 0, "Crafting consumes exactly the declared ingredients");
        player.closeContainer();
        helper.assertValueEqual(count(player.getInventory(), Items.CAKE), 1, "Closing returns the carried result exactly once");
        helper.assertValueEqual(count(player.getInventory(), Items.BUCKET), 0, "Persistent grid remainders are not also returned as duplicated player items");
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertValueEqual(count(loaded.upgradeInventory(upgrade(loaded, 0)), Items.BUCKET), 3, "The crafting input grid survives the real bag codec");
        var reopened = (WorkstationMenus.PortableCrafting) open(player, bag);
        helper.assertValueEqual(count(reopened.grid(), Items.BUCKET), 3, "Reopening restores the three physical remainders");
        helper.assertTrue(reopened.getSlot(0).getItem().isEmpty(), "A saved result preview is never treated as an owned crafted item");
        player.closeContainer();
        helper.succeed();
    }

    static void stonecutterResultsAndSelection(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        var bag = bag(BackpackTier.NETHERITE, UpgradeKind.STONECUTTER);
        bag.upgradeInventory(upgrade(bag, 0)).setItem(0, new ItemStack(Items.STONE, 3));
        var menu = (StonecutterMenu) open(player, bag);
        helper.assertTrue(menu.getNumRecipes() > 0, "Stonecutter loads real enabled recipe displays");
        ResourceLocation slabId = ResourceLocation.withDefaultNamespace("stone_slab_from_stone_stonecutting");
        int selected = -1;
        for (int index = 0; index < menu.getNumRecipes(); index++) {
            if (menu.getRecipes().get(index).id().equals(slabId)) {
                menu.clickMenuButton(player, index);
                selected = index;
                break;
            }
        }
        helper.assertTrue(selected >= 0, "Vanilla stone slab recipe is selectable");
        helper.assertTrue(menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().is(Items.STONE_SLAB)
                && menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().getCount() == 2,
                "The exact vanilla identity previews two slabs despite other recipes with the same result item");
        var choiceState = WorkstationMenus.view(player);
        List<String> choiceIds = List.of(NbtAccess.getStringOr(choiceState, "choices", "").split(","));
        var previews = NbtAccess.getListOrEmpty(choiceState, "choice_results");
        helper.assertValueEqual(previews.size(), choiceIds.size(), "Stonecutting previews align exactly with recipe identities");
        int slabChoice = choiceIds.indexOf(slabId.toString());
        helper.assertTrue(slabChoice >= 0, "The current stonecutting identity appears in the modal choices");
        ItemStack inputBeforeSelection = menu.container.getItem(0).copy();
        ItemStack resultBeforeSelection = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().copy();
        helper.assertTrue(WorkstationMenus.selectRecipe(player, slabId), "The modal selects a currently matching recipe by identity");
        helper.assertTrue(ItemStack.matches(inputBeforeSelection, menu.container.getItem(0))
                && ItemStack.matches(resultBeforeSelection, menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem()),
                "Selecting the current identity again preserves every input and result component");
        var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        ItemStack slabPreview = ItemStack.OPTIONAL_CODEC.parse(ops, previews.get(slabChoice)).getOrThrow();
        helper.assertTrue(ItemStack.matches(slabPreview, menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem()), "Encoded stone result previews use the actual recipe output");
        helper.assertFalse(WorkstationMenus.selectRecipe(player, ResourceLocation.withDefaultNamespace("cake")), "Crafting identities cannot select a stonecutting result");
        helper.assertFalse(menu.clickMenuButton(player, WorkstationMenus.CHOICE_RECIPE_BUTTON + WorkstationMenus.MAX_CHOICES - 1), "A missing modal choice is rejected without guessing a native recipe index");
        int recipe = selected;
        helper.assertFalse(menu.clickMenuButton(foreign, (recipe + 1) % menu.getNumRecipes()), "A foreign player cannot change a portable stonecutter selection");
        helper.assertValueEqual(menu.getSelectedRecipeIndex(), recipe, "Rejected selection preserves the chosen recipe");
        int experience = player.totalExperience;
        menu.clicked(StonecutterMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.STONE_SLAB) && menu.getCarried().getCount() == 2, "Stonecutting uses the actual two-slab recipe yield");
        helper.assertValueEqual(menu.container.getItem(0).getCount(), 2, "One stone is consumed per output batch");
        helper.assertValueEqual(player.totalExperience, experience, "Stonecutting introduces no invented XP charge");
        player.closeContainer();
        helper.assertValueEqual(count(player.getInventory(), Items.STONE_SLAB), 2, "Closing transfers only the taken result");
        var reopened = (StonecutterMenu) open(player, bag);
        helper.assertValueEqual(reopened.container.getItem(0).getCount(), 2, "Unconsumed stone input persists across close and reopen");
        helper.assertValueEqual(reopened.getSelectedRecipeIndex(), recipe, "Stonecutter selection preference persists");
        helper.assertTrue(reopened.getSlot(StonecutterMenu.RESULT_SLOT).getItem().is(Items.STONE_SLAB), "The reopened result is recomputed from real input");
        reopened.container.setItem(0, new ItemStack(Items.ANDESITE));
        ItemStack before = reopened.getSlot(StonecutterMenu.RESULT_SLOT).getItem().copy();
        helper.assertFalse(WorkstationMenus.selectRecipe(player, slabId), "An old modal identity cannot select a recipe after its ingredient changes");
        helper.assertTrue(ItemStack.matches(before, reopened.getSlot(StonecutterMenu.RESULT_SLOT).getItem()), "Rejected stale selection does not replace the current result");
        player.closeContainer();
        helper.assertFalse(WorkstationMenus.selectRecipe(player, slabId), "Closing the native session revokes modal selection authority");
        helper.succeed();
    }

    static void anvilCostsRepairAndRename(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.NETHERITE, UpgradeKind.ANVIL);
        ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
        damaged.setDamageValue(800);
        damaged.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 2);
        Container saved = bag.upgradeInventory(upgrade(bag, 0));
        saved.setItem(0, damaged);
        saved.setItem(1, new ItemStack(Items.DIAMOND, 3));
        var menu = (AnvilMenu) open(player, bag);
        player.connection.handleRenameItem(new ServerboundRenameItemPacket("Restored survey pick"));
        helper.assertTrue(!menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty() && menu.getCost() > 0, "Anvil calculates the real repair and rename cost");
        ItemStack preview = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().copy();
        int cost = menu.getCost();
        helper.assertValueEqual(preview.getDamageValue(), 0, "Three diamonds repair the damaged pick through vanilla logic");
        helper.assertValueEqual(preview.getHoverName().getString(), "Restored survey pick", "Actual vanilla rename packet updates the result");
        menu.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty(), "Insufficient XP cannot take the anvil preview");
        helper.assertValueEqual(menu.getSlot(0).getItem().getDamageValue(), 800, "Failed take leaves the original tool unchanged");
        helper.assertValueEqual(menu.getSlot(1).getItem().getCount(), 3, "Failed take leaves every repair material untouched");
        player.giveExperienceLevels(20);
        int levels = player.experienceLevel;
        menu.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        assertStack(helper, menu.getCarried(), preview, "Paid anvil operation preserves the exact computed item components");
        helper.assertValueEqual(player.experienceLevel, levels - cost, "Anvil deducts the vanilla level cost exactly once");
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty(), "Successful repair consumes one base and the three used materials");
        player.closeContainer();
        helper.assertTrue(bag.upgradeInventory(upgrade(bag, 0)).isEmpty(), "Consumed anvil inputs are not resurrected by persistence");

        ItemStack expensive = new ItemStack(Items.DIAMOND_PICKAXE);
        expensive.setDamageValue(1);
        expensive.set(DataComponents.REPAIR_COST, 63);
        saved.setItem(0, expensive);
        saved.setItem(1, new ItemStack(Items.DIAMOND));
        var costly = (AnvilMenu) open(player, bag);
        helper.assertTrue(costly.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty(), "Survival anvil honors the vanilla too-expensive rule");
        costly.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(costly.getCarried().isEmpty() && costly.getSlot(0).hasItem(), "Forbidden expensive work cannot consume or return a result");
        player.closeContainer();
        helper.succeed();
    }

    static void smithingPreservesComponents(GameTestHelper helper) {
        var player = player(helper);
        var bag = bag(BackpackTier.NETHERITE, UpgradeKind.SMITHING);
        ItemStack base = new ItemStack(Items.DIAMOND_SWORD);
        base.setDamageValue(17);
        base.set(DataComponents.CUSTOM_NAME, Component.literal("Original sword"));
        base.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FIRE_ASPECT), 1);
        Container inputs = bag.upgradeInventory(upgrade(bag, 0));
        inputs.setItem(SmithingMenu.TEMPLATE_SLOT, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        inputs.setItem(SmithingMenu.BASE_SLOT, base);
        inputs.setItem(SmithingMenu.ADDITIONAL_SLOT, new ItemStack(Items.NETHERITE_INGOT));
        var menu = (SmithingMenu) open(player, bag);
        ItemStack preview = menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().copy();
        helper.assertTrue(preview.is(Items.NETHERITE_SWORD), "Portable smithing computes the vanilla transformed item");
        helper.assertValueEqual(preview.getDamageValue(), 17, "Smithing preserves base damage");
        helper.assertValueEqual(preview.getHoverName().getString(), "Original sword", "Smithing preserves custom names");
        helper.assertValueEqual(preview.get(DataComponents.ENCHANTMENTS), base.get(DataComponents.ENCHANTMENTS), "Smithing preserves enchantments");
        int xp = player.totalExperience;
        menu.clicked(SmithingMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        assertStack(helper, menu.getCarried(), preview, "Taking smithing output returns exactly its preview");
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty() && menu.getSlot(2).getItem().isEmpty(), "Smithing consumes template, base, and addition once");
        helper.assertValueEqual(player.totalExperience, xp, "Smithing introduces no XP cost");
        player.closeContainer();
        helper.assertTrue(inputs.isEmpty(), "Consumed smithing inputs persist as empty");

        BagInventory upgradeBag = bag(BackpackTier.DIAMOND, UpgradeKind.STACK_UPGRADE_TIER_4);
        upgradeBag.setItem(107, new ItemStack(Items.DIAMOND, 999));
        upgradeBag.dye(0x2468ac, 0xcafede);
        upgradeBag.remember(0, new ItemStack(Items.EMERALD));
        upgradeBag.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Preserved expedition"));
        ItemStack expected = upgradeBag.stack().transmuteCopy(BackpackRegistry.item(BackpackTier.NETHERITE), 1);
        inputs.setItem(SmithingMenu.TEMPLATE_SLOT, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        inputs.setItem(SmithingMenu.BASE_SLOT, upgradeBag.stack());
        inputs.setItem(SmithingMenu.ADDITIONAL_SLOT, new ItemStack(Items.NETHERITE_INGOT));
        var transforming = (SmithingMenu) open(player, bag);
        assertStack(helper, transforming.getSlot(SmithingMenu.RESULT_SLOT).getItem(), expected, "Actual preserving backpack smithing recipe retains all base components");
        transforming.clicked(SmithingMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        BagInventory result = BagInventory.of(roundTrip(helper.getLevel(), transforming.getCarried()));
        helper.assertValueEqual(result.getContainerSize(), 120, "Smithing unlocks the new tier's storage slots");
        helper.assertValueEqual(result.getItem(107).getCount(), 999, "Smithing and serialization preserve enhanced counts at the old final slot");
        helper.assertTrue(result.canPlaceItem(119, new ItemStack(Items.DIAMOND)), "The newly added storage cells are usable");
        player.closeContainer();
        helper.succeed();
    }

    static void destinationsAndRefill(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.FILTER);
        bag.setItem(0, new ItemStack(Items.OAK_LOG, 100));
        ItemStack named = new ItemStack(Items.OAK_LOG, 19);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved logs"));
        bag.setItem(2, named);
        teach(player, "minecraft:oak_planks");
        var menu = (WorkstationMenus.PortableCrafting) open(player, bag);
        menu.grid().setItem(0, new ItemStack(Items.OAK_LOG));
        helper.assertFalse(menu.quickMoveStack(player, 0).isEmpty(), "Default result shift can craft into backpack storage");
        helper.assertValueEqual(count(bag, Items.OAK_PLANKS), 4, "Default destination owns the four crafted planks");
        helper.assertValueEqual(count(player.getInventory(), Items.OAK_PLANKS), 0, "Default shift does not silently use player inventory");
        helper.assertTrue(menu.grid().isEmpty(), "Grid refill starts disabled");
        helper.assertFalse(menu.clickMenuButton(foreign, WorkstationMenus.REFILL_BUTTON), "A foreign player cannot enable refill");
        helper.assertTrue(menu.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON), "The real refill button changes the server preference");
        menu.grid().setItem(0, new ItemStack(Items.OAK_LOG));
        menu.quickMoveStack(player, 0);
        helper.assertValueEqual(bag.getItem(0).getCount(), 99, "Refill prefers a matching resource from backpack storage");
        helper.assertValueEqual(menu.grid().getItem(0).getCount(), 1, "Refill replaces only the consumed quantity");
        helper.assertTrue(menu.stillValid(player), "Committing a refill cannot overwrite its physical owning backpack with a stale inventory snapshot");
        assertStack(helper, bag.getItem(2), named, "Component-distinct resources cannot be consumed as matching refill");
        bag.setFilter(upgrade(bag, 2), 0, new ItemStack(Items.OAK_LOG));
        bag.updateSettings(upgrade(bag, 2), state -> { state.putString("filter_direction", "OUTPUT"); state.putString("filter_mode", "BLOCK"); });
        player.getInventory().setItem(10, new ItemStack(Items.OAK_LOG, 2));
        menu.quickMoveStack(player, 0);
        helper.assertValueEqual(bag.getItem(0).getCount(), 99, "Refill respects backpack output filters");
        helper.assertValueEqual(player.getInventory().getItem(10).getCount(), 1, "Matching player ingredients are the next refill source");
        bag.updateSettings(upgrade(bag, 2), state -> state.putBoolean("enabled", false));
        bag.setItem(0, new ItemStack(Items.OAK_LOG, 1000));
        int before = count(bag, Items.OAK_PLANKS);
        menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertValueEqual(count(bag, Items.OAK_PLANKS) - before, 256, "A shift operation has a strict 64-batch work bound even with large refill storage");
        helper.assertValueEqual(bag.getItem(0).getCount(), 936, "Bounded shift consumes exactly the 64 replenished ingredients");
        helper.assertValueEqual(menu.grid().getItem(0).getCount(), 1, "The final refill remains an owned physical ingredient");
        helper.assertTrue(menu.clickMenuButton(player, WorkstationMenus.DESTINATION_BUTTON), "The real destination button selects player inventory");
        before = count(bag, Items.OAK_PLANKS);
        menu.quickMoveStack(player, 0);
        helper.assertValueEqual(count(player.getInventory(), Items.OAK_PLANKS), 4, "Selected alternate destination receives the result");
        helper.assertValueEqual(count(bag, Items.OAK_PLANKS), before, "Alternate destination does not duplicate into the backpack");
        helper.assertTrue(menu.stillValid(player), "Refill and shifts preserve the physical source stack");
        player.closeContainer();
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertTrue(NbtAccess.getBooleanOr(loaded.settings(upgrade(loaded, 0)), "grid_refill", false), "Refill preference survives serialization");
        helper.assertValueEqual(NbtAccess.getStringOr(loaded.settings(upgrade(loaded, 0)), "result_destination", ""), "PLAYER", "Destination preference survives serialization");

        BagInventory full = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 64));
        full.setItem(5, new ItemStack(Items.OAK_PLANKS, 63));
        var blocked = (WorkstationMenus.PortableCrafting) open(player, full);
        blocked.grid().setItem(0, new ItemStack(Items.OAK_LOG));
        helper.assertTrue(blocked.quickMoveStack(player, 0).isEmpty(), "A destination that cannot fit a whole recipe batch leaves the craft pending");
        helper.assertValueEqual(blocked.grid().getItem(0).getCount(), 1, "Rejected partial output does not consume the ingredient");
        helper.assertValueEqual(full.getItem(5).getCount(), 63, "Rejected partial output does not insert a fragment or duplicate a preview");
        blocked.clickMenuButton(player, WorkstationMenus.DESTINATION_BUTTON);
        int playerPlanks = count(player.getInventory(), Items.OAK_PLANKS);
        blocked.quickMoveStack(player, 0);
        helper.assertValueEqual(count(player.getInventory(), Items.OAK_PLANKS) - playerPlanks, 4, "Alternate target recovers a craft blocked by full backpack storage");
        player.closeContainer();

        BagInventory anvil = bag(BackpackTier.NETHERITE, UpgradeKind.ANVIL);
        var repair = (AnvilMenu) open(player, anvil);
        player.giveExperienceLevels(20);
        for (int pass = 0; pass < 2; pass++) {
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.setDamageValue(1);
            repair.getSlot(0).set(pick);
            repair.getSlot(1).set(new ItemStack(Items.DIAMOND));
            if (pass == 1) repair.clickMenuButton(player, WorkstationMenus.DESTINATION_BUTTON);
            int targetBefore = count(pass == 0 ? anvil : player.getInventory(), Items.DIAMOND_PICKAXE);
            int cost = repair.getCost(), levels = player.experienceLevel;
            repair.quickMoveStack(player, AnvilMenu.RESULT_SLOT);
            helper.assertValueEqual(count(pass == 0 ? anvil : player.getInventory(), Items.DIAMOND_PICKAXE), targetBefore + 1, "Anvil shift obeys the selected destination");
            helper.assertValueEqual(player.experienceLevel, levels - cost, "Anvil shift still pays the exact vanilla cost");
        }
        helper.assertFalse(repair.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON), "Anvil cannot enable an unsupported refill mode");
        player.closeContainer();

        BagInventory smithing = bag(BackpackTier.NETHERITE, UpgradeKind.SMITHING);
        var smith = (SmithingMenu) open(player, smithing);
        for (int pass = 0; pass < 2; pass++) {
            smith.getSlot(0).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            smith.getSlot(1).set(new ItemStack(Items.DIAMOND_SWORD));
            smith.getSlot(2).set(new ItemStack(Items.NETHERITE_INGOT));
            if (pass == 1) smith.clickMenuButton(player, WorkstationMenus.DESTINATION_BUTTON);
            int targetBefore = count(pass == 0 ? smithing : player.getInventory(), Items.NETHERITE_SWORD);
            smith.quickMoveStack(player, SmithingMenu.RESULT_SLOT);
            helper.assertValueEqual(count(pass == 0 ? smithing : player.getInventory(), Items.NETHERITE_SWORD), targetBefore + 1, "Smithing shift obeys the selected destination");
            helper.assertTrue(smith.getSlot(0).getItem().isEmpty() && smith.getSlot(1).getItem().isEmpty() && smith.getSlot(2).getItem().isEmpty(), "Shift smithing consumes every declared input once");
        }
        helper.assertFalse(smith.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON), "Smithing does not invent automatic input refill");
        player.closeContainer();
        helper.succeed();
    }

    static void craftingConflictSelection(GameTestHelper helper) {
        var player = player(helper);
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        teach(player, "fabricated_backpacks_tests:choice_a");
        teach(player, "fabricated_backpacks_tests:choice_b");
        var menu = (WorkstationMenus.PortableCrafting) open(player, bag);
        menu.grid().setItem(4, new ItemStack(Items.NAUTILUS_SHELL));
        helper.assertValueEqual(NbtAccess.getStringOr(WorkstationMenus.view(player), "choices", "").split(",").length, 2, "Every matching registered recipe is exposed as a validated choice");
        var previews = NbtAccess.getListOrEmpty(WorkstationMenus.view(player), "choice_results");
        var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        helper.assertValueEqual(previews.size(), 2, "Every conflict choice includes one bounded real-item preview");
        helper.assertTrue(ItemStack.OPTIONAL_CODEC.parse(ops, previews.get(0)).getOrThrow().is(Items.AMETHYST_SHARD)
                && ItemStack.OPTIONAL_CODEC.parse(ops, previews.get(1)).getOrThrow().is(Items.COPPER_INGOT), "Conflict previews preserve the same ordering as recipe identities");
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.AMETHYST_SHARD), "Initial conflicting recipe selection is deterministic");
        helper.assertTrue(menu.clickMenuButton(player, WorkstationMenus.NEXT_RECIPE_BUTTON), "Next selects another genuinely matching recipe");
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.COPPER_INGOT), "The selected recipe recomputes its own result");
        helper.assertTrue(menu.clickMenuButton(player, WorkstationMenus.PREVIOUS_RECIPE_BUTTON), "Previous returns to the first matching recipe");
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.AMETHYST_SHARD), "Previous restores the original result");
        ResourceLocation secondId = ResourceLocation.parse("fabricated_backpacks_tests:choice_b");
        helper.assertTrue(WorkstationMenus.selectRecipe(player, secondId), "A modal identity selects the second matching recipe");
        helper.assertFalse(WorkstationMenus.selectRecipe(player, ResourceLocation.withDefaultNamespace("cake")), "A forged unrelated identity is rejected");
        helper.assertFalse(menu.clickMenuButton(player, 100_099), "Forged choice buttons cannot select a nonmatching recipe");
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.COPPER_INGOT), "Taking the chosen result uses the selected recipe");
        helper.assertTrue(menu.grid().getItem(4).is(Items.IRON_NUGGET), "Remainders come from the selected recipe, not the recipe manager's first match");
        helper.assertValueEqual(count(menu.grid(), Items.GOLD_NUGGET) + count(player.getInventory(), Items.GOLD_NUGGET), 0, "The other recipe's remainder is never created");
        player.closeContainer();
        bag.upgradeInventory(upgrade(bag, 0)).setItem(4, new ItemStack(Items.NAUTILUS_SHELL));
        var reopened = (WorkstationMenus.PortableCrafting) open(player, bag);
        helper.assertTrue(reopened.getSlot(0).getItem().is(Items.COPPER_INGOT), "Recipe ID preference survives a real close and reopen");
        boolean prior = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING);
        try {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING).set(true, helper.getLevel().getServer());
            player.resetRecipes(List.of(helper.getLevel().getRecipeManager().byKey(secondId).orElseThrow()));
            helper.assertFalse(WorkstationMenus.selectRecipe(player, secondId), "A previously displayed choice is rechecked against current recipe unlocks");
            helper.assertTrue(reopened.grid().getItem(4).is(Items.NAUTILUS_SHELL), "Locked selection cannot consume the crafting ingredient");
            teach(player, secondId.toString());
            helper.assertTrue(WorkstationMenus.selectRecipe(player, secondId), "The same identity succeeds after its actual server unlock");
        } finally { helper.getLevel().getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING).set(prior, helper.getLevel().getServer()); }
        player.closeContainer();
        helper.succeed();
    }

    static void bucketRefillConservation(GameTestHelper helper) {
        var player = player(helper);
        teach(player, "minecraft:cake");
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        for (int slot = 0; slot < 3; slot++) bag.setItem(slot, new ItemStack(Items.MILK_BUCKET));
        bag.setItem(3, new ItemStack(Items.SUGAR, 2));
        bag.setItem(4, new ItemStack(Items.EGG));
        bag.setItem(5, new ItemStack(Items.WHEAT, 3));
        var menu = (WorkstationMenus.PortableCrafting) open(player, bag);
        cake(menu.grid());
        menu.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.CAKE), "Real cake result is taken before refill");
        helper.assertValueEqual(count(menu.grid(), Items.MILK_BUCKET), 3, "Refill restores all three consumed milk containers");
        helper.assertValueEqual(count(bag, Items.MILK_BUCKET), 0, "Restored milk buckets are removed from their actual source");
        helper.assertValueEqual(count(bag, Items.BUCKET), 3, "All three displaced empty containers are safely stashed");
        helper.assertValueEqual(count(menu.grid(), Items.SUGAR) + count(menu.grid(), Items.EGG) + count(menu.grid(), Items.WHEAT), 6, "Every non-container cake ingredient refills exactly once");
        helper.assertTrue(menu.stillValid(player), "Container refill keeps its owning menu valid");
        player.closeContainer();
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), bag.stack()));
        helper.assertValueEqual(count(loaded, Items.BUCKET) + count(loaded.upgradeInventory(upgrade(loaded, 0)), Items.MILK_BUCKET), 6, "Save preserves every full and empty container owner");

        BagInventory full = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING, UpgradeKind.STACK_UPGRADE_TIER_4);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 1024));
        full.setItem(0, new ItemStack(Items.MILK_BUCKET, 2));
        var blocked = (WorkstationMenus.PortableCrafting) open(player, full);
        for (int slot = 1; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        cake(blocked.grid());
        blocked.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON);
        blocked.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(blocked.getCarried().is(Items.CAKE), "Full remainder destinations cannot undo an already valid craft");
        helper.assertValueEqual(count(blocked.grid(), Items.BUCKET), 3, "A remainder without a safe destination stays in its crafting cell");
        helper.assertValueEqual(count(full, Items.MILK_BUCKET), 2, "Failed refill rolls back the staged source extraction");
        helper.assertValueEqual(count(full, Items.BUCKET) + count(player.getInventory(), Items.BUCKET), 0, "Failed refill does not duplicate a displaced remainder");
        helper.succeed();
    }

    private static void cake(Container grid) {
        for (int slot = 0; slot < 3; slot++) grid.setItem(slot, new ItemStack(Items.MILK_BUCKET));
        grid.setItem(3, new ItemStack(Items.SUGAR));
        grid.setItem(4, new ItemStack(Items.EGG));
        grid.setItem(5, new ItemStack(Items.SUGAR));
        for (int slot = 6; slot < 9; slot++) grid.setItem(slot, new ItemStack(Items.WHEAT));
    }

    static void stonecutterRefillAndRecents(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        BagInventory bag = bag(BackpackTier.NETHERITE, UpgradeKind.STONECUTTER);
        bag.setItem(0, new ItemStack(Items.STONE, 20));
        bag.upgradeInventory(upgrade(bag, 0)).setItem(0, new ItemStack(Items.STONE));
        var menu = (StonecutterMenu) open(player, bag);
        menu.clickMenuButton(player, WorkstationMenus.REFILL_BUTTON);
        java.util.ArrayList<Integer> choices = new java.util.ArrayList<>();
        java.util.HashSet<Item> outputs = new java.util.HashSet<>();
        for (int index = 0; index < menu.getNumRecipes() && choices.size() < 5; index++) {
            menu.clickMenuButton(player, index);
            if (outputs.add(menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().getItem())) choices.add(index);
        }
        helper.assertValueEqual(choices.size(), 5, "Vanilla stone supplies enough distinct results to exercise recent eviction");
        java.util.ArrayList<ResourceLocation> crafted = new java.util.ArrayList<>();
        for (int chosen : choices) {
            menu.clickMenuButton(player, chosen);
            crafted.add(menu.getRecipes().get(chosen).id());
            ItemStack output = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().copy();
            int before = count(bag, output.getItem());
            menu.quickMoveStack(player, StonecutterMenu.RESULT_SLOT);
            helper.assertValueEqual(count(bag, output.getItem()), before + output.getCount(), "Stone result shifts into the preferred backpack destination");
            helper.assertTrue(menu.container.getItem(0).is(Items.STONE) && menu.container.getItem(0).getCount() == 1, "Stone refill leaves one physical input ready");
        }
        helper.assertValueEqual(bag.getItem(0).getCount(), 15, "Five completed recipes consume exactly five refills");
        ResourceLocation stoneType = ResourceLocation.withDefaultNamespace("stonecutting");
        WorkstationHistory history = WorkstationHistory.get(player);
        List<ResourceLocation> recent = history.recipes(player, stoneType, new ItemStack(Items.STONE));
        helper.assertValueEqual(recent, List.of(crafted.get(4), crafted.get(3), crafted.get(2), crafted.get(1)), "Only the last four distinct outputs remain, newest first");
        helper.assertTrue(history.recipes(foreign, stoneType, new ItemStack(Items.STONE)).isEmpty(), "Recent results are private to each player");
        helper.assertTrue(history.recipes(player, stoneType, new ItemStack(Items.ANDESITE)).isEmpty(), "Different ingredients have separate recent history");
        helper.assertTrue(history.recipes(player, ResourceLocation.withDefaultNamespace("smelting"), new ItemStack(Items.STONE)).isEmpty(), "Different recipe types have separate recent history");
        menu.clickMenuButton(player, choices.get(2));
        menu.quickMoveStack(player, StonecutterMenu.RESULT_SLOT);
        helper.assertValueEqual(history.recipes(player, stoneType, new ItemStack(Items.STONE)), List.of(crafted.get(2), crafted.get(4), crafted.get(3), crafted.get(1)), "Recrafting moves one result to the front without a duplicate");
        helper.assertTrue(menu.clickMenuButton(player, WorkstationMenus.RECENT_RECIPE_BUTTON + 1), "A recent result selects its currently available real recipe");
        helper.assertValueEqual(menu.getRecipes().get(menu.getSelectedRecipeIndex()).id(), crafted.get(4), "Recent selection resolves by recipe ID");
        helper.assertFalse(menu.clickMenuButton(foreign, WorkstationMenus.RECENT_RECIPE_BUTTON), "Another player cannot use this session's recent controls");
        String selected = NbtAccess.getStringOr(WorkstationMenus.view(player), "selected_recipe_id", "");
        menu.clickMenuButton(player, WorkstationMenus.DESTINATION_BUTTON);
        ItemStack output = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().copy();
        int playerBefore = count(player.getInventory(), output.getItem());
        menu.quickMoveStack(player, StonecutterMenu.RESULT_SLOT);
        helper.assertValueEqual(count(player.getInventory(), output.getItem()), playerBefore + output.getCount(), "Stonecutter also supports the player result destination");
        var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var encoded = WorkstationHistory.CODEC.encodeStart(ops, history).getOrThrow();
        WorkstationHistory decoded = WorkstationHistory.CODEC.parse(ops, encoded).getOrThrow();
        helper.assertValueEqual(decoded.recipes(player, stoneType, new ItemStack(Items.STONE)), history.recipes(player, stoneType, new ItemStack(Items.STONE)), "World history codec preserves the per-player ordered entries");
        helper.assertTrue(history.isDirty(), "Crafting marks overworld history for actual world persistence");
        player.closeContainer();
        bag.updateSettings(upgrade(bag, 0), state -> state.putInt("selected_recipe", Integer.MAX_VALUE));
        var reopened = (StonecutterMenu) open(player, bag);
        helper.assertValueEqual(reopened.getRecipes().get(reopened.getSelectedRecipeIndex()).id().toString(), selected, "Saved recipe identity wins over a stale numerical index");
        reopened.container.setItem(0, new ItemStack(Items.ANDESITE));
        helper.assertValueEqual(NbtAccess.getStringOr(WorkstationMenus.view(player), "recent_recipes", ""), "", "Changing ingredients does not leak unrelated recents into the UI");
        player.closeContainer();
        helper.succeed();
    }

    static void placedSessionAndStaleGuards(GameTestHelper helper) {
        var player = player(helper);
        var foreign = player(helper);
        BlockPos position = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        var placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        placed.setStack(bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING).stack());
        BackpackMenus.openPlaced(player, placed);
        var origin = (BackpackMenu) player.containerMenu;
        origin.clickMenuButton(player, 100);
        WorkstationMenus.open(player, origin);
        var crafting = (WorkstationMenus.PortableCrafting) player.containerMenu;
        helper.assertValueEqual(placed.viewers(), 1, "Switching from backpack to its workstation transfers one viewer lease");
        helper.assertTrue(helper.getLevel().getBlockState(position).getValue(BackpackBlock.OPEN), "Placed backpack remains visibly open during workstation use");
        crafting.grid().setItem(0, new ItemStack(Items.DIAMOND, 5));
        ItemStack before = placed.stack().copy();
        helper.assertTrue(crafting.quickMoveStack(foreign, 1).isEmpty(), "Foreign direct quick moves cannot mutate a portable crafting session");
        crafting.clicked(Integer.MAX_VALUE, 0, ClickType.PICKUP, player);
        assertStack(helper, placed.stack(), before, "Invalid index and foreign actions preserve all workstation data");
        player.closeContainer();
        helper.assertValueEqual(placed.viewers(), 0, "Closing the workstation releases its final placed viewer");
        helper.assertValueEqual(count(placed.inventory().upgradeInventory(upgrade(placed.inventory(), 0)), Items.DIAMOND), 5, "Closing a placed workstation retains its physical grid");

        for (UpgradeKind kind : List.of(UpgradeKind.CRAFTING, UpgradeKind.STONECUTTER, UpgradeKind.ANVIL, UpgradeKind.SMITHING)) {
            BagInventory bag = bag(BackpackTier.NETHERITE, kind);
            ItemStack input = new ItemStack(kind == UpgradeKind.STONECUTTER ? Items.STONE : kind == UpgradeKind.SMITHING ? Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE : Items.DIAMOND, 3);
            bag.upgradeInventory(upgrade(bag, 0)).setItem(0, input);
            AbstractContainerMenu menu = open(player, bag);
            int index = kind == UpgradeKind.CRAFTING ? 1 : 0;
            helper.assertTrue(menu.quickMoveStack(foreign, index).isEmpty(), "Foreign direct quick move is rejected for " + kind);
            ItemStack snapshot = bag.stack().copy();
            player.getInventory().setItem(0, ItemStack.EMPTY);
            player.getInventory().setItem(1, bag.stack());
            helper.assertFalse(menu.stillValid(player), "Moving the owning bag invalidates " + kind);
            menu.clicked(index, 0, ClickType.PICKUP, player);
            helper.assertTrue(menu.quickMoveStack(player, index).isEmpty(), "Stale direct quick move is rejected for " + kind);
            assertStack(helper, bag.stack(), snapshot, "Every stale workstation action leaves owned data unchanged for " + kind);
            player.closeContainer();
            player.getInventory().setItem(1, ItemStack.EMPTY);
        }
        helper.succeed();
    }
}
