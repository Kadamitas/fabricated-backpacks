package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import com.kadamitas.fabricatedbackpacks.item.BackpackColors;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class RecipeGameTests {
    private RecipeGameTests() {}
    private record Transition(String id, BackpackTier source, BackpackTier target, Item material, int materialCount) {}

    static void shapedUpgradeDataRetention(GameTestHelper helper) {
        var player = player(helper);
        BagInventory workshop = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        var menu = (WorkstationMenus.PortableCrafting) WorkstationGameTests.open(player, workshop);
        var transitions = List.of(
                new Transition("copper_backpack", BackpackTier.LEATHER, BackpackTier.COPPER, Items.COPPER_INGOT, 8),
                new Transition("iron_backpack", BackpackTier.LEATHER, BackpackTier.IRON, Items.IRON_INGOT, 8),
                new Transition("iron_backpack_from_copper", BackpackTier.COPPER, BackpackTier.IRON, Items.IRON_INGOT, 4),
                new Transition("gold_backpack", BackpackTier.IRON, BackpackTier.GOLD, Items.GOLD_INGOT, 8),
                new Transition("diamond_backpack", BackpackTier.GOLD, BackpackTier.DIAMOND, Items.DIAMOND, 8));
        int outputSlot = 12;
        for (Transition transition : transitions) {
            var id = BackpackRegistry.id(transition.id());
            WorkstationGameTests.teach(player, id.toString());
            BagInventory source = bag(transition.source(), UpgradeKind.STACK_UPGRADE_TIER_4);
            source.setItem(source.getContainerSize() - 1, new ItemStack(Items.EMERALD, 999));
            source.remember(0, new ItemStack(Items.DIAMOND));
            source.toggleNoSort(1);
            source.dye(0x345678, 0xbadbad);
            source.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Growing expedition"));
            ItemStack expected = source.stack().transmuteCopy(BackpackRegistry.item(transition.target()), 1);
            player.getInventory().setItem(9, source.stack());
            player.getInventory().setItem(10, new ItemStack(transition.material(), transition.materialCount()));
            helper.assertTrue(WorkstationMenus.transfer(player, id), "Real placement metadata transfers " + transition.id());
            helper.assertTrue(menu.stillValid(player), "Recipe transfer keeps the physical backpack that owns its open session");
            assertStack(helper, menu.getSlot(0).getItem(), expected, "Shaped upgrade preview preserves every source component for " + transition.id());
            menu.clicked(0, 0, ClickType.PICKUP, player);
            assertStack(helper, menu.getCarried(), expected, "Taking the shaped upgrade preserves the original bag identity and contents");
            helper.assertTrue(menu.grid().isEmpty(), "The source backpack and all upgrade material are consumed exactly once");
            helper.assertTrue(player.getInventory().getItem(9).isEmpty() && player.getInventory().getItem(10).isEmpty(), "Transferred ingredients leave no duplicate source items");
            BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), menu.getCarried()));
            helper.assertValueEqual(loaded.getContainerSize(), transition.target().slots(), "Result tier unlocks its actual capacity");
            helper.assertValueEqual(count(loaded, Items.EMERALD), 999, "Shaped transformation and save preserve enhanced item counts");
            helper.assertTrue(loaded.stack().has(BagComponents.MEMORY), "Transformation preserves remembered slots");
            player.getInventory().setItem(outputSlot++, menu.getCarried());
            menu.setCarried(ItemStack.EMPTY);
        }
        player.closeContainer();
        helper.succeed();
    }

    static void dyeRegionsBlendsAndRetention(GameTestHelper helper) {
        var holder = helper.getLevel().getRecipeManager().byKey(BackpackRegistry.id("dye_backpack")).orElseThrow();
        helper.assertTrue(holder.value() instanceof CraftingRecipe, "The actual datapack registers the dye crafting recipe");
        CraftingRecipe recipe = (CraftingRecipe) holder.value();
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_JUKEBOX);
        source.setItem(119, new ItemStack(Items.DIAMOND, 37));
        source.upgradeInventory(upgrade(source, 0)).setItem(11, new ItemStack(Items.MUSIC_DISC_13));
        source.remember(0, new ItemStack(Items.EMERALD));
        source.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Tinted expedition"));
        source.stack().set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(314159));
        BackpackColors.set(source.stack(), 0x123456, 0xfedcba);
        var left = grid(source.stack(), 3, DyeItem.byColor(DyeColor.RED));
        var right = grid(source.stack(), 5, DyeItem.byColor(DyeColor.BLUE));
        var both = grid(source.stack(), 1, DyeItem.byColor(DyeColor.RED));
        var blend = grid(source.stack(), 1, DyeItem.byColor(DyeColor.RED));
        blend.set(3, new ItemStack(DyeItem.byColor(DyeColor.WHITE)));
        blend.set(5, new ItemStack(DyeItem.byColor(DyeColor.BLUE)));
        int red = vanillaColor(DyeColor.RED);
        int blue = vanillaColor(DyeColor.BLUE);
        assertDye(helper, recipe, source.stack(), left, red, 0xfedcba, "Left-side dye changes only the body");
        assertDye(helper, recipe, source.stack(), right, 0x123456, blue, "Right-side dye changes only the trim");
        assertDye(helper, recipe, source.stack(), both, red, red, "Same-column dye changes body and trim together");
        assertDye(helper, recipe, source.stack(), blend,
                vanillaColor(DyeColor.RED, DyeColor.WHITE),
                vanillaColor(DyeColor.RED, DyeColor.BLUE),
                "Each region blends only the dyes that apply to it");
        var invalid = grid(source.stack(), 3, DyeItem.byColor(DyeColor.RED));
        invalid.set(5, bag(BackpackTier.LEATHER).stack());
        helper.assertFalse(recipe.matches(CraftingInput.of(3, 3, invalid), helper.getLevel()), "Two backpacks cannot merge or duplicate their inventories through dyeing");
        invalid.set(5, new ItemStack(Items.STICK));
        helper.assertFalse(recipe.matches(CraftingInput.of(3, 3, invalid), helper.getLevel()), "Unrelated ingredients cannot be consumed by dyeing");

        var player = player(helper);
        var workshop = bag(BackpackTier.NETHERITE, UpgradeKind.CRAFTING);
        var menu = (WorkstationMenus.PortableCrafting) WorkstationGameTests.open(player, workshop);
        for (int slot = 0; slot < 9; slot++) menu.grid().setItem(slot, both.get(slot).copy());
        ItemStack preview = menu.getSlot(0).getItem().copy();
        helper.assertFalse(preview.isEmpty(), "Dye recipe also resolves through the real portable crafting grid");
        menu.clicked(0, 0, ClickType.PICKUP, player);
        assertStack(helper, menu.getCarried(), preview, "The real result take retains every colored backpack component");
        helper.assertTrue(menu.grid().isEmpty(), "Dye crafting consumes exactly one source bag and the one dye");
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), menu.getCarried()));
        helper.assertValueEqual(count(loaded, Items.DIAMOND), 37, "Dyeing preserves main storage after serialization");
        helper.assertTrue(loaded.upgradeInventory(upgrade(loaded, 0)).getItem(11).is(Items.MUSIC_DISC_13), "Dyeing preserves the final advanced record slot");
        player.closeContainer();
        helper.succeed();
    }

    static void cauldronWashConservation(GameTestHelper helper) {
        var player = player(helper);
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.ADVANCED_JUKEBOX);
        source.setItem(119, new ItemStack(Items.DIAMOND, 999));
        source.upgradeInventory(upgrade(source, 1)).setItem(11, new ItemStack(Items.MUSIC_DISC_13));
        source.remember(0, new ItemStack(Items.EMERALD));
        source.toggleNoSort(1);
        source.stack().set(DataComponents.CUSTOM_NAME, Component.literal("Washed expedition"));
        source.stack().set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(314159));
        BackpackColors.set(source.stack(), 0x123456, 0xfedcba);
        ItemStack expected = source.stack().copy();
        BackpackColors.set(expected, BackpackColors.DEFAULT_BODY, BackpackColors.DEFAULT_TRIM);
        player.getInventory().setItem(0, source.stack());
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(pos, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        var context = new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        helper.assertTrue(player.getMainHandItem().useOn(context).consumesAction(), "Using a dyed backpack performs the actual cauldron interaction");
        helper.assertValueEqual(helper.getLevel().getBlockState(pos).getValue(LayeredCauldronBlock.LEVEL), 2, "Washing consumes exactly one water layer");
        assertStack(helper, player.getMainHandItem(), expected, "Washing restores both base colors and preserves every other component");
        player.getMainHandItem().useOn(context);
        helper.assertValueEqual(helper.getLevel().getBlockState(pos).getValue(LayeredCauldronBlock.LEVEL), 2, "A clean backpack does not consume more water");
        assertStack(helper, player.getMainHandItem(), expected, "Repeated clean interaction neither consumes nor duplicates the backpack");
        player.closeContainer();
        BagInventory loaded = BagInventory.of(roundTrip(helper.getLevel(), player.getMainHandItem()));
        helper.assertValueEqual(count(loaded, Items.DIAMOND), 999, "Wash and save preserve enhanced main storage counts");
        helper.assertTrue(loaded.upgradeInventory(upgrade(loaded, 1)).getItem(11).is(Items.MUSIC_DISC_13), "Wash and save preserve the last advanced record slot");
        helper.assertValueEqual(loaded.identity(), source.identity(), "Washing preserves the physical backpack identity");
        BackpackColors.set(source.stack(), 0xff0000, 0x00ff00);
        helper.getLevel().setBlockAndUpdate(pos, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 1));
        player.getMainHandItem().useOn(context);
        helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.CAULDRON), "Washing the last water layer leaves an empty cauldron");
        assertStack(helper, player.getMainHandItem(), expected, "The last-layer wash remains lossless");
        helper.succeed();
    }

    /** Independent expected value comes from the actual 1.21.1 vanilla dye implementation. */
    private static int vanillaColor(DyeColor... colors) {
        ItemStack dyed = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_CHESTPLATE),
                java.util.Arrays.stream(colors).map(DyeItem::byColor).toList());
        return dyed.get(DataComponents.DYED_COLOR).rgb();
    }

    private static List<ItemStack> grid(ItemStack bag, int dyeSlot, Item dye) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) items.add(ItemStack.EMPTY);
        items.set(4, bag.copy());
        items.set(dyeSlot, new ItemStack(dye));
        return items;
    }

    private static void assertDye(GameTestHelper helper, CraftingRecipe recipe, ItemStack original, List<ItemStack> items, int body, int trim, String message) {
        CraftingInput input = CraftingInput.of(3, 3, items);
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "Valid dye layout matches: " + message);
        ItemStack result = recipe.assemble(input, helper.getLevel().registryAccess());
        ItemStack expected = original.copy();
        BackpackColors.set(expected, body, trim);
        assertStack(helper, result, expected, message + "; all unrelated components remain unchanged");
        assertStack(helper, input.items().stream().filter(BackpackRegistry::isBackpack).findFirst().orElseThrow(), original,
                "Preview computation does not mutate its source backpack");
    }
}
