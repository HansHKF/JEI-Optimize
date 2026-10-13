package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.JeiOptimize;
import com.tonywww.jeioptimize.runtime.CategoryVisibilityHost;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.library.focus.FocusGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills {@code recipeCategoriesVisibleCache} with the same ordered result as an empty-focus lookup.
 * The constructor injection is {@code RETURN} only, which Mixin 0.8.5 accepts.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.recipes.RecipeManagerInternal", remap = false)
public abstract class RecipeManagerInternalPrepMixin implements CategoryVisibilityHost.Worker {
    @Shadow(remap = false)
    private List<IRecipeCategory<?>> recipeCategories;

    @Shadow(remap = false)
    private List<IRecipeCategory<?>> recipeCategoriesVisibleCache;

    @Unique
    private int jeiopt$cursor;
    @Unique
    private ArrayList<IRecipeCategory<?>> jeiopt$building;
    @Unique
    private int jeiopt$slices;
    @Unique
    private long jeiopt$startedNs;
    @Unique
    private boolean jeiopt$publishedCache;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void jeiopt$captureManager(CallbackInfo callback) {
        CategoryVisibilityHost.attach(this);
    }

    @Override
    public boolean visibleCacheReady() {
        return recipeCategoriesVisibleCache != null;
    }

    @Override
    public void abandon() {
        jeiopt$building = null;
        jeiopt$cursor = 0;
        if (jeiopt$publishedCache) {
            recipeCategoriesVisibleCache = null;
            jeiopt$publishedCache = false;
        }
    }

    @Override
    public boolean sliceVisibleCategories(long deadlineNanos) {
        if (!CategoryVisibilityHost.isOpen()) {
            return true;
        }
        if (recipeCategoriesVisibleCache != null) {
            return true;
        }
        if (recipeCategories == null) {
            return true;
        }
        if (jeiopt$building == null) {
            jeiopt$building = new ArrayList<>();
            jeiopt$cursor = 0;
            jeiopt$slices = 0;
            jeiopt$startedNs = System.nanoTime();
        }
        IFocusGroup emptyFocus = FocusGroup.EMPTY;
        while (jeiopt$cursor < recipeCategories.size() && System.nanoTime() < deadlineNanos) {
            IRecipeCategory<?> category = recipeCategories.get(jeiopt$cursor++);
            if (!jeiopt$isCategoryHidden(category, emptyFocus)) {
                jeiopt$building.add(category);
            }
        }
        jeiopt$slices++;
        if (jeiopt$cursor < recipeCategories.size()) {
            return false;
        }
        if (!CategoryVisibilityHost.isOpen()) {
            jeiopt$building = null;
            return true;
        }
        recipeCategoriesVisibleCache = List.copyOf(jeiopt$building);
        jeiopt$publishedCache = true;
        JeiOptimize.LOGGER.info(
            "JEI category prep published: categories={}, visible={}, slices={}, prepMs={}",
            recipeCategories.size(),
            jeiopt$building.size(),
            jeiopt$slices,
            (System.nanoTime() - jeiopt$startedNs) / 1_000_000L
        );
        jeiopt$building = null;
        return true;
    }

    @Unique
    private boolean jeiopt$isCategoryHidden(IRecipeCategory<?> category, IFocusGroup focus) {
        return jeiopt$callIsCategoryHidden(category, focus);
    }

    @org.spongepowered.asm.mixin.gen.Invoker("isCategoryHidden")
    protected abstract boolean jeiopt$callIsCategoryHidden(IRecipeCategory<?> category, IFocusGroup focus);
}
