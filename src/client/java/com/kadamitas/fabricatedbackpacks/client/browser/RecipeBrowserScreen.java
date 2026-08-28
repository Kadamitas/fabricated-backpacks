package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserQuery;
import com.kadamitas.fabricatedbackpacks.browser.BrowserTransferResult;
import com.kadamitas.fabricatedbackpacks.browser.NavigationHistory;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A responsive item grid and real recipe layouts, using native widgets and narration. */
final class RecipeBrowserScreen extends Screen {
    private final Screen previous;
    private final int containerId;
    private final int ghostSlot;
    private final NavigationHistory<ViewState> history = new NavigationHistory<>(64);
    private final List<ItemButton> itemButtons = new ArrayList<>();
    private final List<ItemButton> ingredientButtons = new ArrayList<>();
    private ResourceLocation selected = BackpackRegistry.id("backpack");
    private ResourceLocation category;
    private boolean uses;
    private boolean onlyBookmarks;
    private boolean savedRecipes;
    private String query = "";
    private int itemPage;
    private int recipePage;
    private int ingredientRow;
    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private int columns;
    private int rows;
    private long seenVersion = -1;
    private long seenBookmarks = -1;
    private BrowserClientIndex seenIndex;
    private List<BrowserClientIndex.BrowserItem> filtered = List.of();
    private List<BrowserRecipeView> matchingRecipes = List.of();
    private List<ResourceLocation> categories = List.of();
    private Component status;
    private boolean statusError;
    private Component lastStatus;
    private int statusSince;
    private long pendingTransfer;
    private int transferSince;
    private EditBox search;
    private Button backButton, forwardButton, categoryButton, transferButton, maximumButton, itemFavoriteButton, recipeFavoriteButton;
    private Button previousItems, nextItems, previousRecipe, nextRecipe;
    private ResourceLocation hoveredItem;
    private ItemStack hoveredStack = ItemStack.EMPTY;

    RecipeBrowserScreen(Screen previous, int containerId, int ghostSlot) {
        super(Component.translatable("screen.fabricated_backpacks.browser"));
        this.previous = previous;
        this.containerId = containerId;
        this.ghostSlot = ghostSlot;
    }

    @Override protected void init() {
        leftWidth = Math.max(96, Math.min(220, width / 3));
        rightX = leftWidth + 16;
        rightWidth = width - rightX - 8;
        columns = Math.max(1, (leftWidth - 8) / 20);
        rows = Math.max(1, (height - 132) / 20);
        itemButtons.clear();
        ingredientButtons.clear();
        backButton = button("<", 8, 8, 24, () -> navigateHistory(false));
        forwardButton = button(">", 36, 8, 24, () -> navigateHistory(true));
        backButton.setTooltip(Tooltip.create(text("history_back")));
        forwardButton.setTooltip(Tooltip.create(text("history_forward")));
        button(text("close"), width - 49, 8, 41, this::onClose);
        Component searchHelp = text("search_hint");
        search = addRenderableWidget(new EditBox(font, 8, 37, leftWidth, 18, searchHelp));
        search.setMaxLength(BrowserQuery.MAX_LENGTH);
        // Keep the hint concise; the full syntax remains in its tooltip and narration.
        search.setHint(text("search"));
        search.setTooltip(Tooltip.create(searchHelp));
        search.setValue(query);
        search.setResponder(value -> { query = value; itemPage = 0; refreshItems(); });
        button(text("bookmarks"), 8, 59, leftWidth - 48, () -> { onlyBookmarks = !onlyBookmarks; itemPage = 0; refreshItems(); });
        button(text("refresh"), leftWidth - 36, 59, 44, () -> { RecipeBrowserClient.refresh(); status = text("refreshing"); statusError = false; });
        previousItems = button("<", 8, height - 47, 24, () -> { itemPage--; refreshItems(); });
        nextItems = button(">", leftWidth - 16, height - 47, 24, () -> { itemPage++; refreshItems(); });
        button(text("recipes"), rightX, 62, 51, () -> changeMode(false, false));
        button(text("uses"), rightX + 54, 62, 40, () -> changeMode(true, false));
        button(text("saved"), rightX + 97, 62, Math.max(36, rightWidth - 123), () -> changeMode(false, true));
        itemFavoriteButton = button("☆", rightX + rightWidth - 23, 62, 23, this::toggleItemBookmark);
        itemFavoriteButton.setTooltip(Tooltip.create(text("save_item")));
        categoryButton = button(text("all_categories"), rightX, 86, rightWidth - 54, this::cycleCategory);
        previousRecipe = button("<", rightX + rightWidth - 51, 86, 24, () -> changeRecipe(-1));
        nextRecipe = button(">", rightX + rightWidth - 24, 86, 24, () -> changeRecipe(1));
        transferButton = button(text("transfer"), rightX, height - 26, Math.min(105, rightWidth - 80), () -> transfer(false));
        maximumButton = button(text("transfer_max"), rightX + rightWidth - 77, height - 50, 77, () -> transfer(true));
        if (ghostSlot >= 0) {
            button(text("set_filter"), rightX + Math.min(108, rightWidth - 77), height - 26, Math.min(77, rightWidth - 108), () -> {
                if (validContext() && !RecipeBrowserClient.index().item(selected).isEmpty()) {
                    RecipeBrowserClient.selectGhost(selected, containerId, ghostSlot);
                    onClose();
                }
            });
        } else {
            recipeFavoriteButton = button(text("save_recipe"), rightX + rightWidth - 77, height - 26, 77, () -> {
                BrowserRecipeView recipe = currentRecipe();
                if (recipe != null && !RecipeBrowserClient.bookmarks().toggleRecipe(recipe.source().recipe())) bookmarkLimit();
                refreshRecipes();
            });
        }
        refreshItems();
        refreshRecipes();
    }

    private Button button(String label, int x, int y, int width, Runnable action) {
        return button(Component.literal(label), x, y, width, action);
    }
    private Button button(Component label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(label, ignored -> action.run()).bounds(x, y, Math.max(18, width), 18).build());
    }

    private void transfer(boolean maximum) {
        BrowserRecipeView recipe = currentRecipe();
        if (recipe == null || !validContext()) return;
        pendingTransfer = RecipeBrowserClient.transfer(recipe.source().recipe(), containerId, maximum);
        if (pendingTransfer == 0) return;
        transferSince = RecipeBrowserClient.ticks();
        status = text("transferring");
        statusError = false;
        refreshRecipes();
    }

    @Override public void tick() {
        if (!validContext()) {
            minecraft.setScreen(null);
            return;
        }
        if (status != lastStatus) { lastStatus = status; statusSince = RecipeBrowserClient.ticks(); }
        if (status != null && RecipeBrowserClient.ticks() - statusSince > 100) status = null;
        if (pendingTransfer != 0 && RecipeBrowserClient.ticks() - transferSince > 100) {
            pendingTransfer = 0;
            status = text("transfer_timeout");
            statusError = true;
            refreshRecipes();
        }
        BrowserClientIndex index = RecipeBrowserClient.index();
        long bookmarks = RecipeBrowserClient.bookmarks().revision();
        if (seenIndex != index || seenVersion != index.version() || seenBookmarks != bookmarks) {
            seenIndex = index;
            seenVersion = index.version();
            seenBookmarks = bookmarks;
            refreshItems();
            refreshRecipes();
        }
    }

    private boolean validContext() {
        return minecraft.player != null && minecraft.level != null && minecraft.getConnection() != null && minecraft.player.isAlive()
                && minecraft.player.containerMenu.containerId == containerId;
    }

    @Override public void onClose() {
        minecraft.setScreen(validContext() ? previous : null);
    }
    @Override public boolean isPauseScreen() { return false; }

    void transferResult(BrowserTransferResult result) {
        if (pendingTransfer == 0 || result.requestId() != pendingTransfer) return;
        pendingTransfer = 0;
        status = Component.translatable(result.messageKey());
        statusError = !result.success();
        if (result.success()) onClose();
        else refreshRecipes();
    }

    void contextChanged() { refreshRecipes(); }

    private void refreshItems() {
        if (search == null) return;
        for (ItemButton button : itemButtons) removeWidget(button);
        itemButtons.clear();
        try {
            filtered = RecipeBrowserClient.index().search(query).stream()
                    .filter(item -> !onlyBookmarks || RecipeBrowserClient.bookmarks().containsItem(item.id())).toList();
        } catch (IllegalArgumentException invalidQuery) {
            filtered = List.of();
            status = text("query_too_complex");
            statusError = true;
        }
        int perPage = columns * rows;
        int pages = Math.max(1, Math.ceilDiv(filtered.size(), perPage));
        itemPage = Math.clamp(itemPage, 0, pages - 1);
        int startX = 8 + (leftWidth - columns * 20) / 2;
        int begin = itemPage * perPage;
        for (int index = begin; index < Math.min(filtered.size(), begin + perPage); index++) {
            var entry = filtered.get(index);
            int position = index - begin;
            ItemButton button = new ItemButton(startX + position % columns * 20, 81 + position / columns * 20, () -> entry.stack());
            itemButtons.add(addRenderableWidget(button));
        }
        previousItems.active = itemPage > 0;
        nextItems.active = itemPage + 1 < pages;
    }

    private void refreshRecipes() {
        List<BrowserRecipeView> candidates = savedRecipes
                ? RecipeBrowserClient.index().allRecipes().stream().filter(recipe -> RecipeBrowserClient.bookmarks().containsRecipe(recipe.source().recipe())).toList()
                : RecipeBrowserClient.index().recipes(selected, uses);
        categories = candidates.stream().map(recipe -> recipe.source().category()).distinct().sorted().toList();
        if (category != null && !categories.contains(category)) category = null;
        matchingRecipes = candidates.stream().filter(recipe -> category == null || recipe.source().category().equals(category)).toList();
        recipePage = Math.clamp(recipePage, 0, Math.max(0, matchingRecipes.size() - 1));
        previousRecipe.active = recipePage > 0;
        nextRecipe.active = recipePage + 1 < matchingRecipes.size();
        categoryButton.setMessage(category == null ? text("all_categories") : categoryName(category));
        backButton.active = history.canGoBack();
        forwardButton.active = history.canGoForward();
        itemFavoriteButton.setMessage(Component.literal(RecipeBrowserClient.bookmarks().containsItem(selected) ? "★" : "☆"));
        boolean transferContext = RecipeBrowserClient.canTransfer(containerId);
        boolean matchingType = currentRecipe() != null && RecipeBrowserClient.canTransfer(containerId, currentRecipe().source().category());
        boolean unlocked = currentRecipe() == null || currentRecipe().source().unlocked() || !RecipeBrowserClient.limitedCrafting();
        transferButton.active = transferContext && matchingType && unlocked && pendingTransfer == 0;
        maximumButton.active = transferButton.active;
        transferButton.setTooltip(Tooltip.create(text(!transferContext ? "transfer_context" : !matchingType ? "transfer_recipe_type"
                : !unlocked ? "locked" : "transfer_help")));
        maximumButton.setTooltip(Tooltip.create(text(!transferContext ? "transfer_context" : !matchingType ? "transfer_recipe_type"
                : !unlocked ? "locked" : "transfer_max_help")));
        if (recipeFavoriteButton != null) {
            recipeFavoriteButton.active = currentRecipe() != null;
            recipeFavoriteButton.setMessage(currentRecipe() != null && RecipeBrowserClient.bookmarks().containsRecipe(currentRecipe().source().recipe())
                    ? text("unsave_recipe") : text("save_recipe"));
        }
        refreshIngredientButtons();
    }

    private void refreshIngredientButtons() {
        for (ItemButton button : ingredientButtons) removeWidget(button);
        ingredientButtons.clear();
        BrowserRecipeView recipe = currentRecipe();
        if (recipe == null) return;
        int top = 114;
        int availableRows = Math.max(1, (height - 176) / 20);
        int countColumns = Math.min(recipe.columns(), Math.max(1, (rightWidth - 70) / 20));
        int actualRows = Math.ceilDiv(recipe.ingredients().size(), countColumns);
        ingredientRow = Math.clamp(ingredientRow, 0, Math.max(0, actualRows - availableRows));
        for (int index = 0; index < recipe.ingredients().size(); index++) {
            int row = index / countColumns;
            if (row < ingredientRow || row >= ingredientRow + availableRows) continue;
            addIngredient(recipe.ingredients().get(index), rightX + 9 + index % countColumns * 20, top + (row - ingredientRow) * 20);
        }
        if (recipe.layout() == BrowserRecipeView.Layout.FURNACE) addIngredient(recipe.fuel(), rightX + 9, top + 31);
        addIngredient(recipe.results(), rightX + rightWidth - 31, top + Math.max(0, Math.min(1, recipe.rows() - 1)) * 10);
        addIngredient(recipe.stations(), rightX + 4, height - 51);
    }

    private void addIngredient(List<ItemStack> stacks, int x, int y) {
        ItemButton button = new ItemButton(x, y, () -> stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(Math.floorMod(RecipeBrowserClient.ticks() / 20, stacks.size())));
        ingredientButtons.add(addRenderableWidget(button));
    }

    private BrowserRecipeView currentRecipe() {
        return matchingRecipes.isEmpty() ? null : matchingRecipes.get(recipePage);
    }

    private void choose(ItemStack stack, int button) {
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (hasShiftDown() || button == 2) {
            if (!RecipeBrowserClient.bookmarks().toggleItem(id)) bookmarkLimit();
            refreshItems();
            refreshRecipes();
            return;
        }
        boolean nextUses = button == 1;
        select(id, nextUses);
    }

    private void select(ResourceLocation id, boolean useMode) {
        if (selected.equals(id) && uses == useMode && !savedRecipes) return;
        remember();
        selected = id;
        uses = useMode;
        savedRecipes = false;
        category = null;
        recipePage = ingredientRow = 0;
        status = null;
        refreshRecipes();
    }

    private void changeMode(boolean useMode, boolean saved) {
        if (uses == useMode && savedRecipes == saved) return;
        remember();
        uses = useMode;
        savedRecipes = saved;
        category = null;
        recipePage = ingredientRow = 0;
        refreshRecipes();
    }

    private void cycleCategory() {
        if (categories.isEmpty()) return;
        remember();
        int index = category == null ? -1 : categories.indexOf(category);
        category = index + 1 >= categories.size() ? null : categories.get(index + 1);
        recipePage = ingredientRow = 0;
        refreshRecipes();
    }

    private void changeRecipe(int change) {
        recipePage = Math.clamp(recipePage + change, 0, Math.max(0, matchingRecipes.size() - 1));
        ingredientRow = 0;
        refreshRecipes();
    }

    private void toggleItemBookmark() {
        if (!RecipeBrowserClient.bookmarks().toggleItem(selected)) bookmarkLimit();
        refreshItems();
        refreshRecipes();
    }

    private void bookmarkLimit() { status = text("bookmark_limit"); statusError = true; }
    private ViewState state() { return new ViewState(selected, uses, savedRecipes, category, query, itemPage, recipePage, onlyBookmarks); }
    private void remember() {
        history.remember(state());
    }
    private void navigateHistory(boolean moveForward) {
        var destination = moveForward ? history.forward(state()) : history.back(state());
        if (destination.isEmpty()) return;
        ViewState state = destination.get();
        selected = state.item;
        uses = state.uses;
        savedRecipes = state.saved;
        category = state.category;
        query = state.query;
        recipePage = state.recipePage;
        onlyBookmarks = state.bookmarks;
        search.setValue(query);
        itemPage = state.itemPage;
        ingredientRow = 0;
        refreshItems();
        refreshRecipes();
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX < rightX) {
            itemPage += vertical > 0 ? -1 : 1;
            refreshItems();
        } else if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            ingredientRow += vertical > 0 ? -1 : 1;
            refreshIngredientButtons();
        } else {
            changeRecipe(vertical > 0 ? -1 : 1);
        }
        return true;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (net.minecraft.client.gui.screens.Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (net.minecraft.client.gui.screens.Screen.hasAltDown() && keyCode == GLFW.GLFW_KEY_LEFT) { navigateHistory(false); return true; }
        if (net.minecraft.client.gui.screens.Screen.hasAltDown() && keyCode == GLFW.GLFW_KEY_RIGHT) { navigateHistory(true); return true; }
        if (!search.isFocused()) {
            ResourceLocation item = hoveredItem == null ? selected : hoveredItem;
            if (keyCode == GLFW.GLFW_KEY_R) { select(item, false); return true; }
            if (keyCode == GLFW.GLFW_KEY_U) { select(item, true); return true; }
            if (keyCode == GLFW.GLFW_KEY_B) {
                if (!RecipeBrowserClient.bookmarks().toggleItem(item)) bookmarkLimit();
                refreshItems();
                refreshRecipes();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // This screen paints its own backdrop before its native widgets.
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredItem = null;
        hoveredStack = ItemStack.EMPTY;
        graphics.fill(0, 0, width, height, 0xF019252D);
        graphics.fill(5, 33, leftWidth + 11, height - 6, 0xFF24343C);
        graphics.fill(rightX - 3, 33, width - 5, height - 6, 0xFF203139);
        graphics.drawString(font, title, 69, 13, 0xFFE8C89A);
        ItemStack selectedStack = RecipeBrowserClient.index().item(selected);
        Component heading = savedRecipes ? text("saved_recipes") : selectedStack.isEmpty() ? Component.literal(selected.toString()) : selectedStack.getHoverName();
        graphics.drawString(font, font.plainSubstrByWidth(heading.getString(), rightWidth - 4), rightX, 38, 0xFFF1E7D2);
        String mode = text(savedRecipes ? "saved" : uses ? "uses" : "recipes").getString();
        graphics.drawString(font, mode + " · " + (matchingRecipes.isEmpty() ? 0 : recipePage + 1) + "/" + matchingRecipes.size(), rightX, 50, 0xFF9FC3C3);
        int pages = Math.max(1, Math.ceilDiv(filtered.size(), columns * rows));
        graphics.drawCenteredString(font, (itemPage + 1) + "/" + pages, 8 + leftWidth / 2, height - 42, 0xFFD6DEDB);
        graphics.drawString(font, text("item_count", filtered.size()), 10, height - 22, 0xFFC8D1CB);
        BrowserRecipeView recipe = currentRecipe();
        if (recipe == null) {
            graphics.drawWordWrap(font, text(RecipeBrowserClient.total() < 0 || RecipeBrowserClient.received() < RecipeBrowserClient.total()
                    || RecipeBrowserClient.index().building() ? "loading" : "no_recipes"), rightX + 9, 118, rightWidth - 18, 0xFFD2DDD5);
        } else {
            int arrowX = rightX + rightWidth - 58;
            graphics.drawString(font, "→", arrowX, 128, 0xFFE3BD81);
            String id = recipe.source().recipe().toString();
            graphics.drawString(font, font.plainSubstrByWidth(id, rightWidth - 6), rightX + 3, height - 65, 0xFF86ADAE);
            if (mouseY >= height - 67 && mouseY < height - 54 && mouseX >= rightX) {
                com.kadamitas.fabricatedbackpacks.client.screen.ClientText.tooltip(Component.literal(id), mouseX, mouseY);
            }
            String station = recipe.stations().isEmpty() ? categoryName(recipe.source().category()).getString() : recipe.stations().getFirst().getHoverName().getString();
            graphics.drawString(font, font.plainSubstrByWidth(station, Math.max(0, rightWidth - 108)), rightX + 27, height - 47, 0xFFD4D9CC);
            if (recipe.duration() > 0) graphics.drawString(font, text("cooking", recipe.duration() / 20.0F, recipe.experience()), rightX + 34, 150, 0xFF8BAAA8);
            if (!recipe.source().unlocked()) {
                graphics.drawString(font, "◇", rightX + rightWidth - 10, 38, 0xFFE0BC79);
                if (mouseX >= rightX + rightWidth - 15 && mouseY < 58) com.kadamitas.fabricatedbackpacks.client.screen.ClientText.tooltip(text("not_unlocked"), mouseX, mouseY);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!hoveredStack.isEmpty()) graphics.renderTooltip(font, hoveredStack, mouseX, mouseY);
        if (status != null) {
            graphics.fill(5, height - 80, width - 5, height - 54, 0xF0121D26);
            graphics.drawWordWrap(font, status, 10, height - 76, width - 20, statusError ? 0xFFFFAA87 : 0xFF9CE1C6);
        }
        if (mouseY < 29 && mouseX >= 69 && mouseX < width - 53) {
            List<Component> help = new ArrayList<>();
            help.add(text("controls"));
            help.add(text("search_hint"));
            help.add(text("progress", RecipeBrowserClient.received(), Math.max(0, RecipeBrowserClient.total())));
            help.add(text("timings", String.format(java.util.Locale.ROOT, "%.2f", RecipeBrowserClient.serverBuildNanos() / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.2f", RecipeBrowserClient.index().buildNanos() / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.3f", RecipeBrowserClient.index().searchNanos() / 1_000_000.0)));
            if (RecipeBrowserClient.undisplayed() > 0) help.add(text("undisplayed", RecipeBrowserClient.undisplayed()));
            if (RecipeBrowserClient.truncated()) help.add(text("truncated"));
            com.kadamitas.fabricatedbackpacks.client.screen.ClientText.components(font, help, mouseX, mouseY);
        }
    }

    private static Component text(String key, Object... args) { return Component.translatable("browser.fabricated_backpacks." + key, args); }
    private static Component categoryName(ResourceLocation id) {
        String key = "browser.fabricated_backpacks.category." + id.getPath();
        return id.getNamespace().equals("minecraft") ? Component.translatable(key) : Component.literal(id.toString());
    }

    private final class ItemButton extends AbstractButton {
        private final Supplier<ItemStack> source;
        ItemButton(int x, int y, Supplier<ItemStack> source) {
            super(x, y, 18, 18, source.get().isEmpty() ? text("empty_slot") : source.get().getHoverName());
            this.source = source;
        }
        private int clickButton;
        @Override public void onPress() { choose(source.get(), clickButton); }
        @Override protected boolean isValidClickButton(int button) { return button >= 0 && button <= 2; }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            clickButton = button;
            try { return super.mouseClicked(mouseX, mouseY, button); }
            finally { clickButton = 0; }
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            ItemStack stack = source.get();
            graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? 0xFF708B89 : 0xFF13242D);
            graphics.renderOutline(getX(), getY(), 18, 18, isHoveredOrFocused() ? 0xFFE8C68E : 0xFF42595E);
            if (stack.isEmpty()) return;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            graphics.renderFakeItem(stack, getX() + 1, getY() + 1);
            graphics.renderItemDecorations(font, stack, getX() + 1, getY() + 1);
            if (RecipeBrowserClient.bookmarks().containsItem(id)) graphics.fill(getX() + 13, getY() + 1, getX() + 17, getY() + 4, 0xFFFFCF6A);
            if (isHoveredOrFocused()) {
                hoveredItem = id;
                hoveredStack = stack;
            }
            setMessage(stack.getHoverName());
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
    }

    private record ViewState(ResourceLocation item, boolean uses, boolean saved, ResourceLocation category, String query, int itemPage, int recipePage, boolean bookmarks) {}
}
