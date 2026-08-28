package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class UpgradeItem extends Item {
    private final UpgradeKind kind;
    public UpgradeItem(Properties properties, UpgradeKind kind) { super(properties); this.kind = kind; }
    public UpgradeKind kind() { return kind; }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Component.translatable("tooltip.fabricated_backpacks." + kind.id()));
    }
}
