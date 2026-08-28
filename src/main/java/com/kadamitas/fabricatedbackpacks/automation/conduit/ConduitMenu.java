package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/** Configuration for one physically clicked endpoint face; no virtual resource or cross-face controls. */
public final class ConduitMenu extends AbstractContainerMenu {
    private final Player owner;
    private final BlockPos position;
    private final ConduitBundleBlockEntity entity;
    private final Direction openedFace;
    private final int[] state = new int[44];
    private ConduitFilter clientItemFilter = ConduitFilter.EMPTY;
    private ConduitFilter clientFluidFilter = ConduitFilter.EMPTY;
    private ConduitFilterState lastFilters;

    public ConduitMenu(int id, Inventory inventory, BlockPos position) { this(id, inventory.player, position, null, Direction.NORTH); }
    public ConduitMenu(int id, Inventory inventory, ConduitBundleBlockEntity entity, Direction face) {
        this(id, inventory.player, entity.getBlockPos(), entity, face);
    }
    private ConduitMenu(int id, Player owner, BlockPos position, ConduitBundleBlockEntity entity, Direction face) {
        super(ConduitMenus.CONDUIT, id);
        this.owner = owner;
        this.position = position.immutable();
        this.entity = entity;
        this.openedFace = face;
        state[1] = face.ordinal();
        for (int index = 0; index < state.length; index++) {
            final int slot = index;
            addDataSlot(new DataSlot() {
                @Override public int get() { refresh(); return state[slot]; }
                @Override public void set(int value) { state[slot] = value; }
            });
        }
        refresh();
    }
    private void refresh() {
        if (entity == null) return;
        state[1] = openedFace.ordinal();
        state[0] = entity.installedMask();
        for (ConduitKind kind : ConduitKind.values()) {
            for (Direction face : Direction.values()) {
                state[2 + kind.ordinal() * 6 + face.ordinal()] = entity.mode(kind, face).ordinal();
                state[20 + kind.ordinal() * 6 + face.ordinal()] = entity.redstone(kind, face).ordinal();
            }
            state[38 + kind.ordinal()] = ConduitNetworks.networkSize(entity, kind);
            state[41 + kind.ordinal()] = ConduitNetworks.oversized(entity, kind) ? 1 : 0;
        }
    }
    public BlockPos position() { return position; }
    public boolean installed(ConduitKind kind) { return (state[0] & kind.mask()) != 0; }
    public Direction selectedFace() { return entity == null ? Direction.values()[Math.floorMod(state[1], 6)] : openedFace; }
    public ConduitMode mode(ConduitKind kind, Direction face) { return ConduitMode.values()[Math.floorMod(state[2 + kind.ordinal() * 6 + face.ordinal()], 4)]; }
    public ConduitRedstone redstone(ConduitKind kind, Direction face) { return ConduitRedstone.values()[Math.floorMod(state[20 + kind.ordinal() * 6 + face.ordinal()], 3)]; }
    public int networkSize(ConduitKind kind) { return state[38 + kind.ordinal()]; }
    public boolean oversized(ConduitKind kind) { return state[41 + kind.ordinal()] != 0; }
    public ConduitFilter filter(ConduitKind kind) {
        if (kind == ConduitKind.ENERGY) return ConduitFilter.EMPTY;
        if (entity != null) return entity.filter(kind, openedFace);
        return kind == ConduitKind.ITEM ? clientItemFilter : clientFluidFilter;
    }
    public ConduitFilterState filterState() {
        return new ConduitFilterState(containerId, selectedFace(), filter(ConduitKind.ITEM), filter(ConduitKind.FLUID));
    }
    public void applyFilters(ConduitFilterState snapshot) {
        if (entity != null || snapshot.containerId() != containerId) return;
        // Opening data initially contains only the position. This authoritative snapshot can arrive
        // before the vanilla face DataSlot and must not be discarded because the placeholder is NORTH.
        state[1] = snapshot.face().ordinal();
        clientItemFilter = snapshot.itemFilter();
        clientFluidFilter = snapshot.fluidFilter();
    }
    public boolean applyFilterAction(Player player, ConduitFilterAction action) {
        if (entity == null || !(player instanceof ServerPlayer serverPlayer)
                || action.containerId() != containerId || player.containerMenu != this || !stillValid(player)
                || action.kind() == ConduitKind.ENERGY || !entity.has(action.kind())) return false;
        ConduitFilter previous = filter(action.kind());
        ConduitFilter next;
        try {
            next = switch (action.operation()) {
                case SET_MODE -> previous.withMode(ConduitFilterMode.values()[action.index()]);
                case SET_ENTRY -> {
                    Optional<ResourceLocation> id = registeredResource(serverPlayer, action.kind(), action.resource().orElseThrow());
                    yield id.isPresent() ? previous.withEntry(action.index(), id.orElseThrow()) : previous;
                }
                case CLEAR_ENTRY -> previous.withoutEntry(action.index());
            };
        } catch (IllegalArgumentException failure) {
            // For example, putting the same canonical identity into two ghost positions is rejected.
            sendFilters(true);
            return false;
        }
        boolean changed = entity.setFilter(action.kind(), openedFace, next);
        refresh();
        broadcastChanges();
        if (!changed) sendFilters(true);
        return changed;
    }
    private static Optional<ResourceLocation> registeredResource(ServerPlayer player, ConduitKind kind, ResourceLocation id) {
        if (kind == ConduitKind.ITEM) return BuiltInRegistries.ITEM.getOptional(id)
                .filter(item -> item != Items.AIR && new ItemStack(item).isItemEnabled(player.level().enabledFeatures()))
                .map(BuiltInRegistries.ITEM::getKey);
        if (kind != ConduitKind.FLUID) return Optional.empty();
        return BuiltInRegistries.FLUID.getOptional(id).filter(fluid -> !fluid.defaultFluidState().isEmpty()).flatMap(fluid -> {
            try {
                var canonical = FluidVariant.of(fluid).getFluid();
                return Optional.ofNullable(BuiltInRegistries.FLUID.getKey(canonical));
            } catch (IllegalArgumentException failure) { return Optional.empty(); }
        });
    }
    @Override public void sendAllDataToRemote() {
        super.sendAllDataToRemote();
        sendFilters(true);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        sendFilters(false);
    }
    private void sendFilters(boolean force) {
        if (entity == null || !(owner instanceof ServerPlayer player) || player.containerMenu != this
                || !stillValid(player) || !ServerPlayNetworking.canSend(player, ConduitFilterState.TYPE)) return;
        ConduitFilterState snapshot = filterState();
        if (force || !snapshot.equals(lastFilters)) {
            ServerPlayNetworking.send(player, snapshot);
            lastFilters = snapshot;
        }
    }
    @Override public boolean stillValid(Player player) {
        return player == owner && (entity == null ? player.level().isClientSide : entity.stillValid(player));
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean clickMenuButton(Player player, int button) {
        if (entity == null || player.containerMenu != this || !stillValid(player)) return false;
        if (button >= 10 && button < 13) {
            ConduitKind kind = ConduitKind.values()[button - 10];
            if (!entity.has(kind)) return false;
            entity.setMode(kind, selectedFace(), entity.mode(kind, selectedFace()).next());
        } else if (button >= 20 && button < 23) {
            ConduitKind kind = ConduitKind.values()[button - 20];
            if (!entity.has(kind)) return false;
            entity.setRedstone(kind, selectedFace(), entity.redstone(kind, selectedFace()).next());
        } else return false;
        refresh();
        broadcastChanges();
        return true;
    }
}
