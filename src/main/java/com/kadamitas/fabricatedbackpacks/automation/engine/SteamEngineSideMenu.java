package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

/** Native configuration menu: it owns no items and synchronizes only public side permissions. */
public final class SteamEngineSideMenu extends AbstractContainerMenu {
    public static final int DATA_COUNT = 19;
    private final Player owner;
    private final SteamEngineBlockEntity engine;
    private final BlockPos position;
    private final int[] state = new int[DATA_COUNT];

    public SteamEngineSideMenu(int id, Inventory inventory, BlockPos position) {
        this(id, inventory, null, position, Direction.NORTH);
    }
    public SteamEngineSideMenu(int id, Inventory inventory, SteamEngineBlockEntity engine, Direction face) {
        this(id, inventory, engine, engine.getBlockPos(), face);
    }
    private SteamEngineSideMenu(int id, Inventory inventory, SteamEngineBlockEntity engine, BlockPos position, Direction face) {
        super(SteamEngineMenus.SIDES, id);
        this.owner = inventory.player;
        this.engine = engine;
        this.position = position.immutable();
        state[0] = face.ordinal();
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
            state[index(kind, side)] = SteamEngineSides.DEFAULT.mode(kind, side).ordinal();
        for (int index = 0; index < state.length; index++) {
            int field = index;
            addDataSlot(new DataSlot() {
                @Override public int get() { refresh(); return state[field]; }
                @Override public void set(int value) { state[field] = value; }
            });
        }
        refresh();
    }
    private void refresh() {
        if (engine == null) return;
        SteamEngineSides sides = engine.sideConfig();
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values())
            state[index(kind, side)] = sides.mode(kind, side).ordinal();
    }
    private static int index(ConduitKind kind, Direction side) { return 1 + kind.ordinal() * 6 + side.ordinal(); }
    public BlockPos position() { return position; }
    public Direction selectedFace() { return Direction.values()[Math.floorMod(state[0], 6)]; }
    public EngineSideMode mode(ConduitKind kind, Direction side) {
        return EngineSideMode.values()[Math.floorMod(state[index(kind, side)], 4)];
    }
    public EngineSideMode mode(ConduitKind kind) { return mode(kind, selectedFace()); }
    @Override public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && !player.isSpectator()
                && (player.level().isClientSide || engine != null && engine.stillValid(player));
    }
    @Override public boolean clickMenuButton(Player player, int action) {
        if (engine == null || player.containerMenu != this || !stillValid(player)) return false;
        if (action >= 0 && action < 6) state[0] = action;
        else if (action >= 10 && action < 13) {
            ConduitKind kind = ConduitKind.values()[action - 10];
            engine.setSideMode(kind, selectedFace(), engine.sideMode(kind, selectedFace()).next(kind));
        } else return false;
        refresh();
        broadcastChanges();
        return true;
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public void clicked(int slot, int button, ClickType input, Player player) { }
}
