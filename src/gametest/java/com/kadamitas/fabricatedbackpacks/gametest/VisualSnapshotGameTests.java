package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.item.BackpackDisplay;
import com.kadamitas.fabricatedbackpacks.item.BackpackTooltip;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.assertStack;

/** Common tooltip data checks; these do not substitute for client screenshots. */
public final class VisualSnapshotGameTests {
    private VisualSnapshotGameTests() {}

    public static void tooltipSnapshotConservation(GameTestHelper helper) {
        ItemStack backpack = new ItemStack(BackpackRegistry.item(BackpackTier.NETHERITE));
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(47);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Survey pick"));
        ItemStackTemplate stone = ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.STONE));
        ItemStackTemplate namedTool = ItemStackTemplate.fromNonEmptyStack(tool);
        backpack.set(BagComponents.CONTENTS, new InventorySnapshot(120, List.of(
                new InventorySnapshot.Entry(0, stone, Integer.MAX_VALUE),
                new InventorySnapshot.Entry(1, stone, Integer.MAX_VALUE),
                new InventorySnapshot.Entry(119, namedTool, 1))));
        backpack.set(BagComponents.MEMORY, new InventorySnapshot(120,
                List.of(new InventorySnapshot.Entry(8, ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIAMOND)), 1))));
        ItemStack before = backpack.copy();
        var image = backpack.getItem().getTooltipImage(backpack).orElseThrow();
        helper.assertTrue(image instanceof BackpackTooltip, "The actual backpack item exposes its common tooltip component");
        BackpackTooltip snapshot = (BackpackTooltip) image;
        helper.assertValueEqual(snapshot.columns(), 12, "Large-tier tooltip retains its twelve-column layout");
        helper.assertValueEqual(snapshot.contents().size(), 120, "Final sparse cells remain available in the preview");
        helper.assertValueEqual(snapshot.occupiedSlots(), 3, "Empty memory ghosts do not masquerade as physical items");
        helper.assertValueEqual(snapshot.itemCount(), 4_294_967_295L, "Summing enhanced counts uses long arithmetic");
        helper.assertValueEqual(snapshot.contents().entries().getFirst().create().getCount(), Integer.MAX_VALUE,
                "Tooltip templates preserve the full enhanced count");
        assertStack(helper, snapshot.contents().entries().getLast().create(), tool, "Damage and component-distinct item identity survive capture");
        assertStack(helper, backpack, before, "Inspecting a tooltip does not allocate identity or change backpack data");
        ItemStack recreated = snapshot.contents().entries().getFirst().create();
        recreated.setCount(1);
        backpack.set(BagComponents.CONTENTS, InventorySnapshot.EMPTY);
        helper.assertValueEqual(snapshot.itemCount(), 4_294_967_295L, "Later inventory and rendered-stack changes cannot mutate a captured tooltip");
        helper.succeed();
    }

    public static void tooltipSnapshotBounds(GameTestHelper helper) {
        var template = ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.STONE));
        BackpackTooltip snapshot = new BackpackTooltip(100, new InventorySnapshot(999, List.of(
                new InventorySnapshot.Entry(0, template, 7),
                new InventorySnapshot.Entry(0, template, 99),
                new InventorySnapshot.Entry(-1, template, 1),
                new InventorySnapshot.Entry(256, template, 1),
                new InventorySnapshot.Entry(2, template, -1),
                new InventorySnapshot.Entry(255, template, 4))));
        helper.assertValueEqual(snapshot.columns(), 12, "Untrusted preview width is bounded");
        helper.assertValueEqual(snapshot.contents().size(), InventorySnapshot.MAX_SLOTS, "Preview allocation is bounded");
        helper.assertValueEqual(snapshot.occupiedSlots(), 2, "Duplicate, negative-count and outside entries are excluded");
        helper.assertValueEqual(snapshot.itemCount(), 11L, "Rejected entries cannot inflate the preview count");
        helper.succeed();
    }

    public static void displaySelectionRules(GameTestHelper helper) {
        ItemStack backpack = new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER));
        var diamond = ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.DIAMOND));
        var emerald = ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.EMERALD));
        backpack.set(BagComponents.CONTENTS, new InventorySnapshot(120, List.of(new InventorySnapshot.Entry(119, diamond, 1000))));
        backpack.set(BagComponents.MEMORY, new InventorySnapshot(120, List.of(new InventorySnapshot.Entry(119, emerald, 1))));
        CustomData.update(BagComponents.SETTINGS, backpack, tag -> {
            tag.putInt("display_slot", 119); tag.putInt("display_rotation", 409); tag.putInt("display_depth", 999);
        });
        ItemStack before = backpack.copy();
        var display = BackpackDisplay.from(backpack).orElseThrow();
        helper.assertTrue(display.icon().is(Items.DIAMOND), "Physical contents take priority over a memory ghost");
        helper.assertValueEqual(display.icon().getCount(), 1, "An exterior icon does not expose enhanced item counts");
        helper.assertValueEqual(display.rotation(), 45, "Rotation resolves to a legal forty-five-degree step");
        helper.assertValueEqual(display.depth(), 16, "Exterior depth is bounded");
        helper.assertValueEqual(BackpackTooltip.from(backpack).contents().size(), 120, "Saved larger geometry survives a smaller default tier");
        helper.assertValueEqual(BackpackTooltip.from(backpack).columns(), 12, "Saved large geometry uses twelve columns");
        assertStack(helper, backpack, before, "Selection and tooltip inspection do not allocate identities or rewrite components");
        backpack.set(BagComponents.CONTENTS, new InventorySnapshot(120, List.of()));
        helper.assertTrue(BackpackDisplay.from(backpack).orElseThrow().icon().is(Items.EMERALD), "An empty selected cell uses its memory icon");
        var previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"storage\":{\"displayItems\":false}}"));
            helper.assertTrue(BackpackDisplay.from(backpack).isEmpty(), "A synchronized disabled-display rule suppresses the native render selection");
            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":144}}}"));
            ItemStack fresh = new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER));
            ItemStack original = fresh.copy();
            helper.assertValueEqual(BackpackTooltip.from(fresh).contents().size(), 144, "An uninitialized tooltip uses the configured capacity");
            assertStack(helper, fresh, original, "Configured empty previews remain read-only");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }
}
