package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Values are snapshots, never live stacks. Owner fields identify the last accessing player, not an ownership ACL. */
public record BackpackArchive(String identity, String ownerId, String ownerName, String itemName,
                              int bodyColor, int trimColor, long accessedAt, ItemStack backpack) {
    public static final Codec<BackpackArchive> CODEC = RecordCodecBuilder.<BackpackArchive>create(instance -> instance.group(
            Codec.STRING.fieldOf("identity").forGetter(BackpackArchive::identity),
            Codec.STRING.optionalFieldOf("owner_id", "").forGetter(BackpackArchive::ownerId),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(BackpackArchive::ownerName),
            Codec.STRING.fieldOf("item_name").forGetter(BackpackArchive::itemName),
            Codec.intRange(0, 0xffffff).fieldOf("body_color").forGetter(BackpackArchive::bodyColor),
            Codec.intRange(0, 0xffffff).fieldOf("trim_color").forGetter(BackpackArchive::trimColor),
            Codec.LONG.fieldOf("accessed_at").forGetter(BackpackArchive::accessedAt),
            ItemStack.CODEC.fieldOf("backpack").forGetter(BackpackArchive::backpack))
            .apply(instance, BackpackArchive::new)).validate(BackpackArchive::validate);

    public BackpackArchive {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(ownerName);
        Objects.requireNonNull(itemName);
        backpack = Objects.requireNonNull(backpack).copy();
    }
    @Override public ItemStack backpack() { return backpack.copy(); }
    public boolean playerBacked() { return !ownerId.isEmpty(); }
    public boolean sameContents(ItemStack candidate) { return ItemStack.matches(backpack, candidate); }

    static DataResult<BackpackArchive> validate(BackpackArchive value) {
        if (!AdminNames.isIdentity(value.identity) || !value.ownerId.isEmpty() && !AdminNames.isIdentity(value.ownerId)
                || !value.identity.equals(value.backpack.get(BagComponents.IDENTITY))
                || !BackpackRegistry.isBackpack(value.backpack) || value.backpack.getCount() != 1 || value.accessedAt < 0
                || value.bodyColor < 0 || value.bodyColor > 0xffffff || value.trimColor < 0 || value.trimColor > 0xffffff)
            return DataResult.error(() -> "Invalid backpack archive identity, owner, timestamp or stack");
        return DataResult.success(value);
    }
}
