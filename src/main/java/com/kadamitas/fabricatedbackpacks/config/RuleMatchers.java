package com.kadamitas.fabricatedbackpacks.config;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Exact resource ids and #registry tags share the same configuration syntax. */
public final class RuleMatchers {
    private RuleMatchers() { }
    public static boolean item(ItemStack item, Set<String> rules) {
        return !item.isEmpty() && matches(item.getItemHolder(), Registries.ITEM, rules);
    }
    public static boolean entity(Entity entity, Set<String> rules) {
        return matches(entity.getType().builtInRegistryHolder(), Registries.ENTITY_TYPE, rules);
    }
    public static boolean block(BlockState state, Set<String> rules) {
        for (String rule : rules) {
            ResourceLocation id = ResourceLocation.parse(rule.startsWith("#") ? rule.substring(1) : rule);
            if (rule.startsWith("#") ? state.is(TagKey.create(Registries.BLOCK, id))
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).filter(state::is).isPresent()) return true;
        }
        return false;
    }
    private static <T> boolean matches(Holder<T> value, ResourceKey<? extends net.minecraft.core.Registry<T>> registry, Set<String> rules) {
        for (String rule : rules) {
            ResourceLocation id = ResourceLocation.parse(rule.startsWith("#") ? rule.substring(1) : rule);
            if (rule.startsWith("#") ? value.is(TagKey.create(registry, id)) : value.is(ResourceKey.create(registry, id))) return true;
        }
        return false;
    }
}
