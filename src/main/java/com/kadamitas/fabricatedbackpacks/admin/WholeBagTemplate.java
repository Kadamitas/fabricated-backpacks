package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BackpackCopies;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Registry-aware file and saved-data envelope; the version is this mod's own format. */
public record WholeBagTemplate(int format, ItemStack backpack) {
    public static final Codec<WholeBagTemplate> CODEC = RecordCodecBuilder.<WholeBagTemplate>create(instance -> instance.group(
            Codec.intRange(1, 1).fieldOf("format").forGetter(WholeBagTemplate::format),
            ItemStack.CODEC.fieldOf("backpack").forGetter(WholeBagTemplate::backpack))
            .apply(instance, WholeBagTemplate::new)).validate(value -> valid(value.backpack)
            ? DataResult.success(value) : DataResult.error(() -> "Template must contain exactly one registered backpack"));
    public WholeBagTemplate { backpack = Objects.requireNonNull(backpack).copy(); }
    @Override public ItemStack backpack() { return backpack.copy(); }
    public ItemStack instantiate() { return BackpackCopies.fork(backpack); }
    public static WholeBagTemplate capture(ItemStack stack) {
        if (!valid(stack)) throw new IllegalArgumentException("Hold exactly one backpack");
        return new WholeBagTemplate(1, stack);
    }
    private static boolean valid(ItemStack stack) { return !stack.isEmpty() && stack.getCount() == 1 && BackpackRegistry.isBackpack(stack); }
}
