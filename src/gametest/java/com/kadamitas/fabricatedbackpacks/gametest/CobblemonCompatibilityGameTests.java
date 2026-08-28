package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilter;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterAction;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMode;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.MobCapture;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Opt-in tests against the real dependency. Server-player fixtures are not client or UI evidence. */
public final class CobblemonCompatibilityGameTests extends ContentGameTests {
    private static final String TEMPLATE = "fabricated_backpacks_tests:platform";
    private static final String BATCH = "cobblemon_compatibility";
    private static final long WATER_AMOUNT = FluidConstants.BUCKET / 4 + 17;
    private static final long ENERGY_AMOUNT = 3_210;

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public void cobblemonItemsKeepComponentsThroughGhostFilteredTransfer(GameTestHelper helper) {
        requireCobblemon(helper);
        Item poke = cobblemonItem(helper, "poke_ball");
        Item great = cobblemonItem(helper, "great_ball");
        ItemStack first = named(poke, 17, "Saved Poké Ball sample");
        ItemStack second = named(poke, 5, "Different Poké Ball components");
        ItemStack denied = named(great, 11, "Saved Great Ball sample");
        List<ItemStack> expected = List.of(first, second, denied);
        BagInventory source = bag(BackpackTier.NETHERITE);
        source.setItem(0, denied.copy());
        source.setItem(1, first.copy());
        source.setItem(2, second.copy());
        source.save();

        ItemStack decoded = roundTrip(helper.getLevel(), source.stack());
        assertStack(helper, decoded, source.stack(), "The actual backpack ItemStack codec retains every Cobblemon component and count");
        Link link = link(helper, decoded, bag(BackpackTier.NETHERITE).stack(), ConduitKind.ITEM);
        ServerPlayer editor = player(helper);
        ItemStack beforeGhost = link.source().stack().copy();
        editFilter(helper, editor, link, ConduitKind.ITEM, ConduitFilterMode.ALLOW, id(poke));
        assertStack(helper, link.source().stack(), beforeGhost, "A native registry-ID ghost edit does not consume or rewrite real balls");
        helper.assertTrue(editor.getInventory().isEmpty() && editor.containerMenu.getCarried().isEmpty(),
                "Ghost selection creates no real player or cursor item");
        helper.assertTrue(link.pipe().filter(ConduitKind.ITEM, Direction.EAST).equals(ConduitFilter.EMPTY),
                "Editing the source interface leaves the other physical face unchanged");

        helper.runAfterDelay(45, () -> {
            helper.assertValueEqual(count(link.destination().inventory(), poke), 22,
                    "Natural server ticks route both named variants of the whitelisted real Poké Ball");
            helper.assertValueEqual(count(link.destination().inventory(), great), 0,
                    "The first, denied Great Ball slot is skipped without admitting it");
            helper.assertValueEqual(count(link.source().inventory(), great), 11, "Every denied ball remains at its physical source");
            conserved(helper, link, expected);
            assertStack(helper, roundTrip(helper.getLevel(), link.destination().stack()), link.destination().stack(),
                    "Transferred Cobblemon components persist through another native ItemStack codec round trip");
            helper.runAfterDelay(12, () -> {
                conserved(helper, link, expected);
                helper.assertValueEqual(count(link.destination().inventory(), poke), 22, "Further routing ticks do not duplicate admitted balls");
                completeWithCaptureExclusion(helper, editor);
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 220)
    public void cobblemonFiltersCoexistWithFluidAndEnergy(GameTestHelper helper) {
        requireCobblemon(helper);
        Item poke = cobblemonItem(helper, "poke_ball");
        Item great = cobblemonItem(helper, "great_ball");
        Item ultra = cobblemonItem(helper, "ultra_ball");
        ItemStack allowedFirst = named(poke, 13, "Coexisting Poké Ball sample");
        ItemStack blocked = named(great, 9, "Blocked then admitted Great Ball sample");
        ItemStack allowedSecond = named(ultra, 7, "Coexisting Ultra Ball sample");
        List<ItemStack> expected = List.of(allowedFirst, blocked, allowedSecond);
        BagInventory source = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.BATTERY);
        BagInventory destination = bag(BackpackTier.NETHERITE, UpgradeKind.TANK, UpgradeKind.BATTERY);
        destination.updateSettings(upgrade(destination, 1), settings -> settings.putBoolean("external_output", false));
        source.setItem(0, blocked.copy());
        source.setItem(1, allowedFirst.copy());
        source.setItem(2, allowedSecond.copy());
        FluidVariant water = FluidVariant.of(Fluids.WATER, DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Cobblemon coexistence water")).build());
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(ResourceRuntime.fluidStorage(source).insert(water, WATER_AMOUNT, transaction), WATER_AMOUNT,
                    "The source owns a finite, component-bearing water quantity including fractional millibuckets");
            var battery = ResourceRuntime.energyStorage(source);
            for (long charged = 0; charged < ENERGY_AMOUNT; ) {
                long accepted = battery.insert(ENERGY_AMOUNT - charged, transaction);
                helper.assertTrue(accepted > 0 && accepted <= ENERGY_AMOUNT - charged, "Finite battery charging makes bounded progress");
                charged += accepted;
            }
            transaction.commit();
        }
        source.save();
        destination.save();
        Link link = link(helper, roundTrip(helper.getLevel(), source.stack()),
                roundTrip(helper.getLevel(), destination.stack()), ConduitKind.values());
        helper.assertTrue(!link.destination().energyStorage(Direction.WEST).supportsExtraction(),
                "The real receiving backpack battery is input-only");
        ServerPlayer editor = player(helper);
        editFilter(helper, editor, link, ConduitKind.ITEM, ConduitFilterMode.BLOCK, id(great));
        editFilter(helper, editor, link, ConduitKind.FLUID, ConduitFilterMode.ALLOW, BuiltInRegistries.FLUID.getKey(Fluids.WATER));

        ConduitFilter blocklist = link.pipe().filter(ConduitKind.ITEM, Direction.WEST);
        ConduitFilter allowlist = blocklist.withMode(ConduitFilterMode.ALLOW);
        List<ResourceLocation> realCatalog = BuiltInRegistries.ITEM.keySet().stream()
                .filter(resource -> resource.getNamespace().equals("cobblemon")).toList();
        helper.assertTrue(realCatalog.size() >= 3, "The dependency supplies an actual Cobblemon item registry");
        for (ResourceLocation resource : realCatalog) {
            helper.assertTrue(blocklist.matches(resource) == !resource.equals(id(great)),
                    "BLOCK targets one exact registry identity, not the whole Cobblemon namespace: " + resource);
            helper.assertTrue(allowlist.matches(resource) == resource.equals(id(great)),
                    "ALLOW does not accidentally admit other Cobblemon registry identities: " + resource);
        }

        helper.runAfterDelay(45, () -> {
            helper.assertValueEqual(count(link.destination().inventory(), poke), 13, "The first nonblocked Cobblemon item routes normally");
            helper.assertValueEqual(count(link.destination().inventory(), ultra), 7, "A second real Cobblemon item routes through the same interface");
            helper.assertValueEqual(count(link.destination().inventory(), great), 0, "The blacklisted Great Ball never enters the recipient");
            conserved(helper, link, expected);
            resourcesArrived(helper, link, water);
            editFilter(helper, editor, link, ConduitKind.ITEM, ConduitFilterMode.ALLOW, id(great));
            helper.assertTrue(link.pipe().has(ConduitKind.ITEM) && link.pipe().has(ConduitKind.FLUID) && link.pipe().has(ConduitKind.ENERGY),
                    "Changing an item filter leaves the three physical lanes installed");
            helper.runAfterDelay(35, () -> {
                helper.assertValueEqual(count(link.destination().inventory(), great), 9, "A live native ALLOW edit admits the formerly blocked item");
                helper.assertTrue(link.source().inventory().isEmpty(), "Each admitted physical item leaves the source exactly once");
                conserved(helper, link, expected);
                resourcesArrived(helper, link, water);
                assertStack(helper, roundTrip(helper.getLevel(), link.destination().stack()), link.destination().stack(),
                        "Cobblemon items, water and battery components coexist in the saved recipient backpack");
                helper.runAfterDelay(12, () -> {
                    conserved(helper, link, expected);
                    resourcesArrived(helper, link, water);
                    helper.succeed();
                });
            });
        });
    }

    /** Real dependency entity, but no simulated party or battle: this verifies the generic-capture exclusion only. */
    private static void completeWithCaptureExclusion(GameTestHelper helper, ServerPlayer player) {
        var level = helper.getLevel();
        var pokemonId = ResourceLocation.fromNamespaceAndPath("cobblemon", "pokemon");
        var pokemonType = BuiltInRegistries.ENTITY_TYPE.getOptional(pokemonId).orElseThrow();
        var exclusion = TagKey.create(Registries.ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath("fabricated_backpacks", "unsupported_capture"));
        helper.assertTrue(pokemonType.builtInRegistryHolder().is(exclusion),
                "The actual registered Pokemon entity belongs to the optional unsupported_capture tag");

        BagInventory catcher = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_MOB_CATCHER);
        catcher.updateSettings(upgrade(catcher, 0), tag -> tag.putBoolean("enabled", true));
        catcher.setItem(catcher.getContainerSize() - 1, new ItemStack(Items.DIAMOND, 7));
        catcher.save();
        ItemStack beforeBag = catcher.stack().copy();
        ItemStack beforeUpgrade = catcher.upgrades().getItem(0).copy();
        helper.assertTrue(upgrade(catcher, 0).kind() == UpgradeKind.ADVANCED_MOB_CATCHER
                        && NbtAccess.getBooleanOr(catcher.settings(upgrade(catcher, 0)), "enabled", false),
                "The refusal fixture has a real installed, enabled advanced catcher");
        player.setPos(helper.absoluteVec(new Vec3(3.5, 1, 6.5)));
        helper.assertTrue(!player.isSpectator() && player.getInventory().isEmpty() && player.containerMenu.getCarried().isEmpty(),
                "The connected survival-player fixture starts without granted items or a cursor stack");

        var created = pokemonType.create(level);
        helper.assertTrue(created instanceof Mob && created instanceof TamableAnimal && created.getType() == pokemonType,
                "The native registry factory creates the actual living, ownable Pokemon entity, not a vanilla stand-in");
        Mob pokemon = (Mob) created;
        TamableAnimal ownable = (TamableAnimal) pokemon;
        pokemon.setNoAi(true);
        pokemon.setHealth(pokemon.getMaxHealth());
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 1, 5.5));
        pokemon.moveTo(at.x, at.y, at.z, 0, 0);
        try {
            helper.assertTrue(level.addFreshEntity(pokemon), "The real Pokemon is registered in the native server level");
            helper.assertTrue(pokemon.isAlive() && !pokemon.isPassenger() && !pokemon.isVehicle()
                            && pokemon.level() == player.level()
                            && pokemon.distanceToSqr(player) <= player.entityInteractionRange() * player.entityInteractionRange()
                            && level.mayInteract(player, pokemon.blockPosition()),
                    "The live Pokemon is in capture range and passes the ordinary entity/player authority preconditions");
            helper.assertTrue(ownable.getOwnerUUID() == null, "The native Pokemon starts without an owner flag");
            for (boolean owned : new boolean[]{false, true}) {
                // The inherited owner flag exercises the ownable gate, without claiming a real party/battle fixture.
                if (owned) ownable.setOwnerUUID(player.getUUID());
                helper.assertTrue(!owned || player.getUUID().equals(ownable.getOwnerUUID()),
                        "The owned-entity flag belongs to this player, so the foreign-owner guard cannot mask the exclusion");
                CompoundTag beforeEntity = new CompoundTag();
                helper.assertTrue(pokemon.save(beforeEntity), "The actual Pokemon can be serialized by generic Entity.save");
                helper.assertFalse(MobCapture.capture(catcher, pokemon, player),
                        "Generic capture refuses the real Pokemon with owner flag " + owned);
                CompoundTag afterEntity = new CompoundTag();
                helper.assertTrue(pokemon.isAlive() && !pokemon.isRemoved() && level.getEntity(pokemon.getUUID()) == pokemon
                                && pokemon.save(afterEntity) && afterEntity.equals(beforeEntity),
                        "Refusal preserves the exact native entity identity, owner and serialized data");
                assertStack(helper, catcher.stack(), beforeBag, "Pokemon refusal leaves every bag component and real item unchanged");
                helper.assertTrue(NbtAccess.getListOrEmpty(catcher.settings(), "captured_entities").isEmpty()
                                && catcher.settings().getIntArray("captured_slots").length == 0,
                        "Refusal creates no stored entity or reserved capture cells");
            }
        } finally {
            pokemon.discard();
        }

        var pig = helper.spawn(EntityType.PIG, new BlockPos(4, 1, 5));
        pig.setNoAi(true);
        pig.setHealth(7);
        var pigId = pig.getUUID();
        helper.assertTrue(MobCapture.capture(catcher, pig, player),
                "The same enabled catcher and player really capture an ordinary nearby native pig");
        helper.assertTrue(!pig.isAlive() && !catcher.canRemoveUpgrade(0)
                        && NbtAccess.getListOrEmpty(catcher.settings(), "captured_entities").size() == 1,
                "The positive control removes one pig and reserves one capture, rather than rejecting everything");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getEntity(pigId) == null, "The native world has finished removing the captured pig");
            helper.assertTrue(MobCapture.release(catcher, 0, player, helper.absoluteVec(new Vec3(4.5, 1, 5.5))),
                    "The positive-control pig releases through the real capture API");
            var released = level.getEntity(pigId);
            helper.assertTrue(released instanceof Mob && released.getType() == EntityType.PIG && released.isAlive(),
                    "Exactly the original pig UUID returns as a live native pig");
            helper.assertTrue(NbtAccess.getListOrEmpty(catcher.settings(), "captured_entities").isEmpty()
                            && catcher.settings().getIntArray("captured_slots").length == 0 && catcher.canRemoveUpgrade(0),
                    "The control releases all capture reservations and leaves the catcher removable");
            helper.assertFalse(MobCapture.release(catcher, 0, player, helper.absoluteVec(new Vec3(4.5, 1, 5.5))),
                    "A second release cannot grant another pig");
            helper.assertValueEqual(count(catcher, Items.DIAMOND), 7, "The control conserves every real stored item");
            assertStack(helper, catcher.upgrades().getItem(0), beforeUpgrade, "The control does not consume or replace the catcher upgrade");
            helper.assertTrue(player.getInventory().isEmpty() && player.containerMenu.getCarried().isEmpty(),
                    "Refusal and positive control grant or consume no player or cursor items");
            released.discard();
            helper.succeed();
        });
    }

    private static void requireCobblemon(GameTestHelper helper) {
        var dependency = FabricLoader.getInstance().getModContainer("cobblemon");
        helper.assertTrue(dependency.isPresent(), "This opt-in test requires the actual Cobblemon mod; absence is a failure, never a skip");
        helper.assertValueEqual(dependency.orElseThrow().getMetadata().getVersion().getFriendlyString(), "1.7.3+1.21.1",
                "The compatibility fixture must use the pinned official Cobblemon release");
    }

    private static Item cobblemonItem(GameTestHelper helper, String path) {
        ResourceLocation resource = ResourceLocation.fromNamespaceAndPath("cobblemon", path);
        Item item = BuiltInRegistries.ITEM.getOptional(resource).orElse(Items.AIR);
        helper.assertTrue(item != Items.AIR && item.getDefaultInstance().isItemEnabled(helper.getLevel().enabledFeatures()),
                "The actual dependency must register the enabled item " + resource);
        return item;
    }

    private static ResourceLocation id(Item item) { return BuiltInRegistries.ITEM.getKey(item); }

    private static ItemStack named(Item item, int count, String label) {
        ItemStack stack = item.getDefaultInstance().copyWithCount(count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return stack;
    }

    private record Link(BackpackBlockEntity source, BackpackBlockEntity destination, ConduitBundleBlockEntity pipe) {}

    private static Link link(GameTestHelper helper, ItemStack source, ItemStack destination, ConduitKind... kinds) {
        BackpackBlockEntity from = placeBag(helper, new BlockPos(2, 2, 3), source);
        BackpackBlockEntity to = placeBag(helper, new BlockPos(4, 2, 3), destination);
        BlockPos position = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.getLevel().setBlock(position, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState(), 3);
        var pipe = (ConduitBundleBlockEntity) helper.getLevel().getBlockEntity(position);
        helper.assertTrue(pipe != null, "The actual conduit block creates its native block entity");
        for (ConduitKind kind : kinds) {
            helper.assertTrue(pipe.install(kind), "The real bundle accepts exactly one " + kind + " lane");
            for (Direction side : Direction.values()) pipe.setMode(kind, side, ConduitMode.DISABLED);
            pipe.setMode(kind, Direction.WEST, ConduitMode.EXTRACT);
            pipe.setMode(kind, Direction.EAST, ConduitMode.INSERT);
        }
        pipe.refreshVisual();
        for (ConduitKind kind : kinds) for (Direction side : List.of(Direction.WEST, Direction.EAST))
            helper.assertTrue(pipe.visualState().endpoint(kind, side), "A real backpack exposes its " + kind + " interface on " + side);
        return new Link(from, to, pipe);
    }

    private static BackpackBlockEntity placeBag(GameTestHelper helper, BlockPos relative, ItemStack stack) {
        BlockPos position = helper.absolutePos(relative);
        helper.getLevel().setBlock(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState(), 3);
        var entity = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        helper.assertTrue(entity != null, "A physical backpack block entity exists");
        entity.setStack(stack);
        return entity;
    }

    /** Exercise the production server ghost-action validator using a connected server-player fixture. */
    private static void editFilter(GameTestHelper helper, ServerPlayer editor, Link link, ConduitKind kind,
                                   ConduitFilterMode mode, ResourceLocation resource) {
        Vec3 at = Vec3.atCenterOf(link.pipe().getBlockPos()).add(0, 0, 2);
        editor.setPos(at.x, at.y, at.z);
        var menu = new ConduitMenu(77, editor.getInventory(), link.pipe(), Direction.WEST);
        editor.containerMenu = menu;
        try {
            helper.assertTrue(menu.stillValid(editor), "The ghost edit is authorized for this live physical interface");
            ConduitFilter before = menu.filter(kind);
            for (int row : before.entries().keySet()) helper.assertTrue(menu.applyFilterAction(editor,
                    new ConduitFilterAction(menu.containerId, kind, ConduitFilterAction.Operation.CLEAR_ENTRY, row, Optional.empty())),
                    "Clearing an existing native ghost row succeeds");
            helper.assertTrue(menu.applyFilterAction(editor, new ConduitFilterAction(menu.containerId, kind,
                    ConduitFilterAction.Operation.SET_ENTRY, 0, Optional.of(resource))), "The native ghost validator accepts the real registry identity");
            if (menu.filter(kind).mode() != mode) helper.assertTrue(menu.applyFilterAction(editor,
                    new ConduitFilterAction(menu.containerId, kind, ConduitFilterAction.Operation.SET_MODE, mode.ordinal(), Optional.empty())),
                    "The native allow/block mode change succeeds");
            helper.assertTrue(menu.filter(kind).equals(new ConduitFilter(mode, Map.of(0, resource))),
                    "The active interface owns exactly the requested ghost policy");
        } finally { editor.closeContainer(); }
    }

    private static void conserved(GameTestHelper helper, Link link, List<ItemStack> expected) {
        for (ItemStack reference : expected) {
            int amount = 0;
            for (var endpoint : List.of(link.source(), link.destination())) {
                BagInventory inventory = endpoint.inventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (ItemStack.isSameItemSameComponents(stack, reference)) amount += stack.getCount();
                    helper.assertTrue(stack.isEmpty() || expected.stream().anyMatch(sample -> ItemStack.isSameItemSameComponents(stack, sample)),
                            "Transport introduces no unknown item or altered component variant");
                }
            }
            helper.assertValueEqual(amount, reference.getCount(), "Source plus recipient conserve " + reference.getHoverName().getString());
        }
    }

    private static void resourcesArrived(GameTestHelper helper, Link link, FluidVariant water) {
        long sourceWater = fluidAmount(helper, link.source(), water);
        long destinationWater = fluidAmount(helper, link.destination(), water);
        helper.assertValueEqual(sourceWater + destinationWater, WATER_AMOUNT, "Item filtering conserves every independent water droplet");
        helper.assertValueEqual(destinationWater, WATER_AMOUNT, "The shared fluid lane delivered the finite water source");
        long sourceEnergy = ResourceRuntime.batteryStored(link.source().inventory(), 1);
        long destinationEnergy = ResourceRuntime.batteryStored(link.destination().inventory(), 1);
        helper.assertValueEqual(sourceEnergy + destinationEnergy, ENERGY_AMOUNT, "Item filtering conserves every independent energy unit");
        helper.assertValueEqual(destinationEnergy, ENERGY_AMOUNT, "The shared energy lane filled only the input-only recipient");
    }

    private static long fluidAmount(GameTestHelper helper, BackpackBlockEntity bag, FluidVariant expected) {
        long amount = 0;
        for (var view : ResourceRuntime.fluidStorage(bag.inventory())) {
            helper.assertTrue(view.getAmount() == 0 || view.getResource().equals(expected), "Routing preserves the exact fluid component variant");
            if (view.getResource().equals(expected)) amount += view.getAmount();
        }
        return amount;
    }
}
