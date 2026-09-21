package com.kadamitas.fabricatedbackpacks.equipment;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.core.Registry;
import com.kadamitas.fabricatedbackpacks.platform.NativeEvents.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

/** One native backpack slot, independent of the player's armor and hands. */
public final class BackpackEquipment {
    private record Live(ItemStack attachment, BagInventory inventory) {}
    private static final java.util.Map<Player, Live> LIVE = new java.util.WeakHashMap<>();
    public static final AttachmentType<ItemStack> EQUIPPED = Registry.register(NeoForgeRegistries.ATTACHMENT_TYPES, BackpackRegistry.id("equipped_backpack"),
            AttachmentType.builder(() -> ItemStack.EMPTY).serialize(ItemStack.OPTIONAL_CODEC.fieldOf("value")).copyOnDeath()
                    .sync((holder, recipient) -> holder == recipient, ItemStack.OPTIONAL_STREAM_CODEC).build());
    // Visuals contain no inventory data and are rebuilt on login/respawn. They do
    // not need serialization merely to satisfy NeoForge's copy-on-death contract.
    public static final AttachmentType<ItemStack> VISUAL = Registry.register(NeoForgeRegistries.ATTACHMENT_TYPES, BackpackRegistry.id("equipped_visual"),
            AttachmentType.builder(() -> ItemStack.EMPTY).sync(ItemStack.OPTIONAL_STREAM_CODEC).build());
    private BackpackEquipment() {}
    public static ItemStack get(Player player) { return player.getData(EQUIPPED); }
    public static ItemStack visual(Player player) { return player.getData(VISUAL); }
    private static void synchronizeVisual(Player player) {
        ItemStack visual = com.kadamitas.fabricatedbackpacks.item.BackpackVisuals.snapshot(get(player));
        if (!ItemStack.matches(visual(player), visual)) player.setData(VISUAL, visual);
    }
    public static java.util.Optional<BagInventory> inventory(Player player) {
        ItemStack attached = get(player);
        if (!BackpackRegistry.isBackpack(attached)) { LIVE.remove(player); return java.util.Optional.empty(); }
        Live live = LIVE.get(player);
        if (live == null || live.attachment() != attached) {
            live = new Live(attached, BagInventory.of(attached));
            LIVE.put(player, live);
        }
        return java.util.Optional.of(live.inventory());
    }
    public static boolean isCurrent(Player player, BagInventory inventory) {
        Live live = LIVE.get(player);
        return live != null && live.attachment() == get(player) && live.inventory() == inventory;
    }
    public static boolean setFromInventory(Player player, BagInventory inventory) {
        if (!isCurrent(player, inventory)) return false;
        ItemStack published = inventory.stack().copy();
        player.setData(EQUIPPED, published);
        LIVE.put(player, new Live(published, inventory));
        synchronizeVisual(player);
        return true;
    }
    public static void set(Player player, ItemStack stack) {
        if (!stack.isEmpty() && (!BackpackRegistry.isBackpack(stack) || stack.getCount() != 1)) throw new IllegalArgumentException("Invalid backpack equipment");
        player.setData(EQUIPPED, stack.copy());
        LIVE.remove(player);
        synchronizeVisual(player);
    }
    public static void initialize() {
        com.kadamitas.fabricatedbackpacks.platform.NativeEvents.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> synchronizeVisual(handler.player));
        com.kadamitas.fabricatedbackpacks.platform.NativeEvents.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> synchronizeVisual(newPlayer));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player) || player.level().getGameRules().get(GameRules.KEEP_INVENTORY)) return;
            ItemStack equipped = get(player);
            if (equipped.isEmpty()) return;
            set(player, ItemStack.EMPTY);
            if (!net.minecraft.world.item.enchantment.EnchantmentHelper.has(equipped,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                player.drop(equipped.copy(), true, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        });
    }
}
