package com.kadamitas.fabricatedbackpacks.item;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public final class UpgradeItem extends Item {
    private final UpgradeKind kind;
    public UpgradeItem(Properties properties, UpgradeKind kind) { super(properties); this.kind = kind; }
    public UpgradeKind kind() { return kind; }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                           Consumer<Component> lines, TooltipFlag flags) {
        lines.accept(Component.translatable("tooltip.fabricated_backpacks." + kind.id()));
    }
}
