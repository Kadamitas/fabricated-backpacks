package com.kadamitas.fabricatedbackpacks.menu;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;

import java.util.ArrayList;
import java.util.List;

/** Small per-player histories belong to the world, not to whichever backpack happens to be open. */
public final class WorkstationHistory extends SavedData {
    private record Recent(ResourceLocation recipe, ItemStack result) {
        private static final Codec<Recent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("recipe").forGetter(Recent::recipe),
                ItemStack.CODEC.fieldOf("result").forGetter(Recent::result)).apply(instance, Recent::new));
    }
    private record Scope(String player, ResourceLocation type, ItemStack ingredient, List<Recent> recent) {
        private static final Codec<Scope> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player").forGetter(Scope::player),
                ResourceLocation.CODEC.fieldOf("type").forGetter(Scope::type),
                ItemStack.CODEC.fieldOf("ingredient").forGetter(Scope::ingredient),
                Recent.CODEC.listOf(0, 4).fieldOf("recent").forGetter(Scope::recent)).apply(instance, Scope::new));
    }
    public static final Codec<WorkstationHistory> CODEC = Scope.CODEC.listOf().xmap(WorkstationHistory::new, history -> List.copyOf(history.scopes));
    public static final SavedData.Factory<WorkstationHistory> TYPE = new SavedData.Factory<>(WorkstationHistory::new,
            (tag, registries) -> CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag.get("entries"))
                    .result().orElseGet(WorkstationHistory::new), DataFixTypes.LEVEL);
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("entries", CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), this).getOrThrow());
        return tag;
    }
    private final List<Scope> scopes;

    private WorkstationHistory() { this(List.of()); }
    private WorkstationHistory(List<Scope> scopes) { this.scopes = new ArrayList<>(scopes); }
    public static WorkstationHistory get(ServerPlayer player) { return player.serverLevel().getServer().overworld().getDataStorage().computeIfAbsent(TYPE, "fabricated_backpacks_workstation_history"); }

    public List<ResourceLocation> recipes(ServerPlayer player, ResourceLocation type, ItemStack ingredient) {
        Scope scope = find(player.getUUID().toString(), type, ingredient);
        return scope == null ? List.of() : scope.recent().stream().map(Recent::recipe).toList();
    }

    public void remember(ServerPlayer player, ResourceLocation type, ItemStack ingredient, ResourceLocation recipe, ItemStack result) {
        if (ingredient.isEmpty() || result.isEmpty()) return;
        String owner = player.getUUID().toString();
        Scope previous = find(owner, type, ingredient);
        List<Recent> recent = new ArrayList<>();
        recent.add(new Recent(recipe, result.copyWithCount(1)));
        if (previous != null) for (Recent entry : previous.recent()) {
            if (recent.size() < 4 && !ItemStack.isSameItemSameComponents(entry.result(), result)) recent.add(entry);
        }
        if (previous != null) scopes.remove(previous);
        scopes.add(new Scope(owner, type, ingredient.copyWithCount(1), List.copyOf(recent)));
        // Keep a generous bounded number of ingredient scopes for each player.
        while (scopes.stream().filter(scope -> scope.player().equals(owner)).count() > 256) {
            scopes.remove(scopes.stream().filter(scope -> scope.player().equals(owner)).findFirst().orElseThrow());
        }
        setDirty();
    }

    private Scope find(String player, ResourceLocation type, ItemStack ingredient) {
        return scopes.stream().filter(scope -> scope.player().equals(player) && scope.type().equals(type)
                && ItemStack.isSameItemSameComponents(scope.ingredient(), ingredient)).findFirst().orElse(null);
    }
}
