package com.kadamitas.fabricatedbackpacks.client.compat;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.client.automation.ConduitScreen;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.fabric.ingredients.fluids.IJeiFluidIngredient;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Loaded solely by JEI's optional Fabric entrypoint; no common or normal client code links its API. */
public final class FabricatedBackpacksJeiPlugin implements IModPlugin {
    @Override public ResourceLocation getPluginUid() { return BackpackRegistry.id("conduit_filters"); }

    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(ConduitScreen.class, new IGuiContainerHandler<ConduitScreen>() {
            @Override public List<Rect2i> getGuiExtraAreas(ConduitScreen screen) {
                return screen.filterPanelBounds().map(FabricatedBackpacksJeiPlugin::rectangle).stream().toList();
            }
        });
        registration.addGhostIngredientHandler(ConduitScreen.class, new FilterGhosts());
    }

    private static Rect2i rectangle(ScreenRectangle bounds) {
        return new Rect2i(bounds.left(), bounds.top(), bounds.width(), bounds.height());
    }

    private static final class FilterGhosts implements IGhostIngredientHandler<ConduitScreen> {
        @Override public <I> List<Target<I>> getTargetsTyped(ConduitScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
            var kind = screen.selectedFilterKind();
            if (kind.isEmpty() || identity(kind.get(), ingredient.getIngredient()).isEmpty()) return List.of();
            return screen.filterTargets().stream().filter(target -> target.kind() == kind.get())
                    .<Target<I>>map(target -> new GhostTarget<>(rectangle(target.bounds()), value -> {
                        if (Minecraft.getInstance().screen != screen || screen.selectedFilterKind().orElse(null) != target.kind()) return;
                        identity(target.kind(), value).ifPresent(id -> {
                            if (target.kind() == ConduitKind.ITEM) screen.acceptItem(target.slot(), id);
                            else screen.acceptFluid(target.slot(), id);
                        });
                    })).toList();
        }

        @Override public void onComplete() {}
    }

    private record GhostTarget<I>(Rect2i area, Consumer<I> selection) implements IGhostIngredientHandler.Target<I> {
        @Override public Rect2i getArea() { return area; }
        @Override public void accept(I ingredient) { selection.accept(ingredient); }
    }

    private static Optional<ResourceLocation> identity(ConduitKind kind, Object ingredient) {
        if (kind == ConduitKind.ITEM) return ingredient instanceof ItemStack stack && !stack.isEmpty()
                ? Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem())) : Optional.empty();
        if (kind != ConduitKind.FLUID) return Optional.empty();
        if (ingredient instanceof IJeiFluidIngredient fluid) return fluidId(fluid.getFluidVariant());
        if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) return Optional.empty();
        // Read a constant copy: a bucket is a convenient fluid selector, never a transferred item.
        var storage = ContainerItemContext.withConstant(stack.copyWithCount(1)).find(FluidStorage.ITEM);
        if (storage == null) return Optional.empty();
        ResourceLocation selected = null;
        int inspected = 0;
        for (var view : storage) {
            if (++inspected > 64) return Optional.empty();
            if (view.isResourceBlank() || view.getAmount() <= 0) continue;
            var id = fluidId(view.getResource());
            if (id.isEmpty()) continue;
            if (selected != null && !selected.equals(id.get())) return Optional.empty();
            selected = id.get();
        }
        return Optional.ofNullable(selected);
    }

    private static Optional<ResourceLocation> fluidId(FluidVariant fluid) {
        return fluid.isBlank() ? Optional.empty() : Optional.of(BuiltInRegistries.FLUID.getKey(fluid.getFluid()));
    }
}
