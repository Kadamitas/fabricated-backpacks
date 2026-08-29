package com.kadamitas.fabricatedbackpacks.automation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** Keep installation and interaction guidance on hover, outside the compact machine screens. */
public class AutomationBlockItem extends BlockItem {
    public AutomationBlockItem(Block block, Properties properties) { super(block, properties); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                          Consumer<Component> lines, TooltipFlag flags) {
        super.appendHoverText(stack, context, display, lines, flags);
        lines.accept(Component.translatable("tooltip.fabricated_backpacks." + BuiltInRegistries.ITEM.getKey(this).getPath()));
    }
}
