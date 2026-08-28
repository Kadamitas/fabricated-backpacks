package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.RuleMatchers;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.EmptyItemFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.FullItemFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import team.reborn.energy.api.EnergyStorage;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Server entry points for transactional item, fluid, energy, and experience operations. */
public final class ResourceRuntime {
    private static boolean registered;
    private ResourceRuntime() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ResourceComponents.register();
        ItemStorage.SIDED.registerForBlockEntity((entity, direction) -> {
            // Null invites Fabric's generic Container fallback, which would bypass this policy.
            if (!connectionAllowed(entity.getLevel(), entity.getBlockPos(), direction)) return Storage.empty();
            BagInventory bag = entity.inventory();
            return TraversalResources.items(bag, direction, () -> current(entity, bag, direction), entity::setChanged);
        }, BackpackRegistry.BLOCK_ENTITY);
        FluidStorage.SIDED.registerForBlockEntity((entity, direction) -> {
            if (!connectionAllowed(entity.getLevel(), entity.getBlockPos(), direction)) return null;
            BagInventory bag = entity.inventory();
            return TraversalResources.fluids(bag, true, () -> current(entity, bag, direction), entity::setChanged);
        }, BackpackRegistry.BLOCK_ENTITY);
        EnergyStorage.SIDED.registerForBlockEntity((entity, direction) -> {
            if (entity.getLevel() != null && entity.getLevel().isClientSide) return entity.energyStorage(direction);
            if (!connectionAllowed(entity.getLevel(), entity.getBlockPos(), direction)) return null;
            return entity.energyStorage(direction);
        }, BackpackRegistry.BLOCK_ENTITY);
        var backpacks = Arrays.stream(BackpackTier.values()).map(BackpackRegistry::item)
                .toArray(net.minecraft.world.item.Item[]::new);
        ItemStorage.ITEM.registerForItems((stack, context) -> BackpackItemAccess.items(context), backpacks);
        FluidStorage.ITEM.registerForItems((stack, context) -> BackpackConfig.get().storage().itemFluidAccess()
                ? BackpackItemAccess.fluids(context) : null, backpacks);
        EnergyStorage.ITEM.registerForItems((stack, context) -> BackpackItemAccess.energy(context), backpacks);
        FluidStorage.combinedItemApiProvider(Items.EXPERIENCE_BOTTLE).register(context ->
                new FullItemFluidStorage(context, Items.GLASS_BOTTLE, ResourceComponents.experience(),
                        FluidAmount.dropletsForMb(160)));
        FluidStorage.combinedItemApiProvider(Items.GLASS_BOTTLE).register(context ->
                new EmptyItemFluidStorage(context, empty -> ItemVariant.of(Items.EXPERIENCE_BOTTLE),
                        ResourceComponents.EXPERIENCE, FluidAmount.dropletsForMb(160)));
    }

    public static Storage<FluidVariant> fluidStorage(BagInventory bag) { return fluids(bag, true); }
    public static EnergyStorage energyStorage(BagInventory bag) { return TraversalResources.energy(bag); }
    public static Storage<ItemVariant> itemStorage(BagInventory bag, Direction direction) {
        return TraversalResources.items(bag, direction, () -> true, () -> {});
    }

    /** No entity lookup is invented: integrations explicitly select a server player's native equipped slot. */
    public static ContainerItemContext equippedContext(ServerPlayer player) { return new BackpackEquipmentContext(player); }

    /** An adapter retained by a machine must not address a replaced block or bypass later connection changes. */
    private static boolean current(BackpackBlockEntity entity, BagInventory bag, Direction direction) {
        return !entity.isRemoved() && entity.getLevel() instanceof ServerLevel && entity.stack() == bag.stack()
                && connectionAllowed(entity.getLevel(), entity.getBlockPos(), direction);
    }

    public static boolean connectionAllowed(Level level, BlockPos position, Direction direction) {
        var rules = BackpackConfig.get().storage();
        if (rules.disableConnections() || level == null) return false;
        if (direction == null) return true;
        BlockPos neighbor = position.relative(direction);
        return level.hasChunkAt(neighbor) && !RuleMatchers.block(level.getBlockState(neighbor), rules.blockedConnections());
    }

    /** Menu admission follows real APIs; result slots remain output-only. */
    public static boolean isValidAuxiliary(UpgradeKind kind, int slot, ItemStack item) {
        if (item.isEmpty() || BackpackRegistry.isBackpack(item)) return false;
        ContainerItemContext context = ContainerItemContext.withConstant(item.copyWithCount(1));
        return switch (kind) {
            case TANK -> slot >= 0 && slot < 2 && context.find(FluidStorage.ITEM) != null;
            case BATTERY -> slot >= 0 && slot < 2 && context.find(EnergyStorage.ITEM) != null;
            default -> false;
        };
    }

    static Storage<FluidVariant> fluids(BagInventory bag, boolean rateLimited) {
        return TraversalResources.fluids(bag, rateLimited);
    }

    static BackpackTank tank(BagInventory bag, int slot, boolean rateLimited) {
        return bag.installedUpgrades().stream().filter(u -> u.slot() == slot && u.kind() == UpgradeKind.TANK)
                .findFirst().map(u -> new BackpackTank(bag, u, rateLimited)).orElse(null);
    }

    /** Explicitly selected tank access stays local, while honoring this bag's intentional admission policy. */
    public static Storage<FluidVariant> tankStorage(BagInventory bag, int slot, boolean rateLimited) {
        BackpackTank tank = tank(bag, slot, rateLimited);
        return tank == null ? Storage.empty() : new VoidFluidStorage(bag, tank,
                () -> BackpackRegistry.isBackpack(bag.stack()) && bag.stack().getCount() == 1 && tank.getCapacity() > 0);
    }

    public static FluidVariant fluidFilter(BagInventory bag, int upgradeSlot, int row) {
        InstalledUpgrade upgrade = bag.installedUpgrades().stream()
                .filter(candidate -> candidate.slot() == upgradeSlot && candidate.kind().family().equals("void")).findFirst().orElse(null);
        if (upgrade == null || row < 0 || row >= fluidFilterSlots(bag, upgradeSlot)) return FluidVariant.blank();
        var filters = upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.<FluidVariant>of());
        return row < filters.size() ? filters.get(row) : FluidVariant.blank();
    }

    public static int fluidFilterSlots(BagInventory bag, int upgradeSlot) {
        InstalledUpgrade upgrade = bag.installedUpgrades().stream()
                .filter(candidate -> candidate.slot() == upgradeSlot && candidate.kind().family().equals("void")).findFirst().orElse(null);
        return upgrade == null ? 0 : Math.min(ResourceComponents.MAX_FLUID_FILTERS, Math.max(bag.filterSlots(upgrade),
                upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.<FluidVariant>of()).size()));
    }

    public static Component fluidFilterDescription(BagInventory bag, int upgradeSlot, int row) {
        FluidVariant fluid = fluidFilter(bag, upgradeSlot, row);
        Component name = fluid.isBlank() ? Component.translatable("tooltip.fabricated_backpacks.fluid_filter.empty")
                : fluid.getComponentMap().getOrDefault(DataComponents.CUSTOM_NAME, FluidVariantAttributes.getName(fluid));
        var description = Component.translatable("tooltip.fabricated_backpacks.fluid_filter", row + 1, name);
        if (!fluid.isBlank() && !fluid.getComponents().isEmpty())
            description.append(Component.translatable("tooltip.fabricated_backpacks.fluid_filter.components"));
        return description;
    }

    /** Trusted server/API edit; network callers must obtain the variant from an authoritative container. */
    public static boolean setFluidFilter(BagInventory bag, int upgradeSlot, int row, FluidVariant fluid) {
        if (fluid == null) return false;
        InstalledUpgrade upgrade = bag.installedUpgrades().stream()
                .filter(candidate -> candidate.slot() == upgradeSlot && candidate.kind().family().equals("void")).findFirst().orElse(null);
        if (upgrade == null || row < 0 || row >= fluidFilterSlots(bag, upgradeSlot)) return false;
        List<FluidVariant> filters = new ArrayList<>(upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.<FluidVariant>of())
                .stream().limit(ResourceComponents.MAX_FLUID_FILTERS).toList());
        while (filters.size() <= row) filters.add(FluidVariant.blank());
        filters.set(row, fluid);
        while (!filters.isEmpty() && filters.getLast().isBlank()) filters.removeLast();
        if (filters.isEmpty()) upgrade.stack().remove(ResourceComponents.VOID_FLUID_FILTERS);
        else upgrade.stack().set(ResourceComponents.VOID_FLUID_FILTERS, List.copyOf(filters));
        bag.save();
        return true;
    }

    static BackpackBattery battery(BagInventory bag) {
        return bag.installedUpgrades().stream().filter(u -> u.kind() == UpgradeKind.BATTERY)
                .findFirst().map(u -> new BackpackBattery(bag, u)).orElse(null);
    }

    public static long tankStoredMb(BagInventory bag, int slot) {
        BackpackTank tank = tank(bag, slot, false);
        return tank == null ? 0 : tank.getAmount() / FluidAmount.DROPLETS_PER_MB;
    }

    public static long tankCapacityMb(BagInventory bag, int slot) {
        BackpackTank tank = tank(bag, slot, false);
        return tank == null ? 0 : tank.getCapacity() / FluidAmount.DROPLETS_PER_MB;
    }

    public static FluidVariant tankFluid(BagInventory bag, int slot) {
        BackpackTank tank = tank(bag, slot, false);
        return tank == null ? FluidVariant.blank() : tank.getResource();
    }

    public static long batteryStored(BagInventory bag, int slot) {
        return bag.installedUpgrades().stream().filter(u -> u.slot() == slot && u.kind() == UpgradeKind.BATTERY)
                .findFirst().map(u -> new BackpackBattery(bag, u).getAmount()).orElse(0L);
    }

    public static long batteryCapacity(BagInventory bag, int slot) {
        return bag.installedUpgrades().stream().filter(u -> u.slot() == slot && u.kind() == UpgradeKind.BATTERY)
                .findFirst().map(u -> new BackpackBattery(bag, u).getCapacity()).orElse(0L);
    }

    public static long offerExperience(BagInventory bag, long points) {
        try (Transaction transaction = Transaction.openOuter()) {
            long accepted = insertExperience(bag, points, transaction);
            if (accepted > 0) transaction.commit();
            return accepted;
        }
    }

    static long insertExperience(BagInventory bag, long points, TransactionContext transaction) {
        if (points < 0) throw new IllegalArgumentException("Negative experience");
        long requested = Math.min(points, Long.MAX_VALUE / FluidAmount.DROPLETS_PER_XP);
        Storage<FluidVariant> storage = fluids(bag, false);
        FluidVariant experience = ResourceComponents.experience();
        long accepted = StorageUtil.simulateInsert(storage, experience,
                requested * FluidAmount.DROPLETS_PER_XP, transaction) / FluidAmount.DROPLETS_PER_XP;
        if (accepted == 0) return 0;
        try (Transaction nested = transaction.openNested()) {
            long exact = accepted * FluidAmount.DROPLETS_PER_XP;
            if (storage.insert(experience, exact, nested) != exact) return 0;
            nested.commit();
            return accepted;
        }
    }

    static long extractExperience(BagInventory bag, long points, TransactionContext transaction) {
        if (points < 0) throw new IllegalArgumentException("Negative experience");
        long requested = Math.min(points, Long.MAX_VALUE / FluidAmount.DROPLETS_PER_XP);
        Storage<FluidVariant> storage = fluids(bag, false);
        FluidVariant experience = ResourceComponents.experience();
        long available = StorageUtil.simulateExtract(storage, experience,
                requested * FluidAmount.DROPLETS_PER_XP, transaction) / FluidAmount.DROPLETS_PER_XP;
        if (available == 0) return 0;
        try (Transaction nested = transaction.openNested()) {
            long exact = available * FluidAmount.DROPLETS_PER_XP;
            if (storage.extract(experience, exact, nested) != exact) return 0;
            nested.commit();
            return available;
        }
    }

    public static void tick(BagInventory bag, ServerLevel level, BlockPos position, LivingEntity carrier) {
        var rules = BackpackConfig.get().upgrades();
        List<InstalledUpgrade> installed = bag.installedUpgrades();
        for (InstalledUpgrade upgrade : installed) {
            if (upgrade.kind() == UpgradeKind.TANK && level.getGameTime() % rules.tank().containerTicks() == 0) {
                ResourceContainers.tank(bag, upgrade);
            } else if (upgrade.kind() == UpgradeKind.BATTERY) {
                ResourceContainers.battery(bag, upgrade);
            } else if (upgrade.kind().family().equals("pump")) {
                PumpRuntime.tick(bag, upgrade, level, position, carrier);
            } else if (upgrade.kind() == UpgradeKind.XP_PUMP && level.getGameTime() % rules.experience().interval() == 0) {
                ExperienceRuntime.tick(bag, upgrade, level, position, carrier);
            }
        }
        ExperienceRuntime.collect(bag, level, position, carrier);
    }

    public static void action(BagInventory bag, int upgradeSlot, String action, ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) return;
        InstalledUpgrade upgrade = bag.installedUpgrades().stream()
                .filter(u -> u.slot() == upgradeSlot).findFirst().orElse(null);
        if (upgrade == null) return;
        if (upgrade.kind().family().equals("void") && action.matches("fluid_filter:[0-9]{1,2}")) {
            int row = Integer.parseInt(action.substring("fluid_filter:".length()));
            ItemStack cursor = player.containerMenu.getCarried();
            if (cursor.isEmpty()) setFluidFilter(bag, upgradeSlot, row, FluidVariant.blank());
            else {
                Storage<FluidVariant> contents = ContainerItemContext.withConstant(cursor.copyWithCount(1)).find(FluidStorage.ITEM);
                FluidVariant selected = contents == null ? null : StorageUtil.findStoredResource(contents);
                if (selected != null) setFluidFilter(bag, upgradeSlot, row, selected);
            }
            return;
        }
        if (PumpRuntime.sameBag(bag, player.containerMenu.getCarried())) return;
        if (upgrade.kind() == UpgradeKind.TANK && action.equals("container")) {
            ResourceContainers.exchangeCursor(tankStorage(bag, upgradeSlot, false), ContainerItemContext.ofPlayerCursor(player, player.containerMenu));
        } else if (upgrade.kind() == UpgradeKind.BATTERY && action.equals("container")) {
            ResourceContainers.exchangeEnergy(new BackpackBattery(bag, upgrade),
                    ContainerItemContext.ofPlayerCursor(player, player.containerMenu));
        } else if (upgrade.kind() == UpgradeKind.XP_PUMP) {
            ExperienceRuntime.action(bag, upgrade, action, player);
        } else if (upgrade.kind().family().equals("pump")) {
            PumpRuntime.action(bag, upgrade, action);
        }
    }
}
