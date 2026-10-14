package com.tonywww.jeioptimize.recipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import java.util.List;
import java.util.stream.Stream;

public final class RecipeIngredientAccess {
    private RecipeIngredientAccess() { }

    public static boolean canFlatten(Ingredient ingredient) {
        //? if >=26.1 {
        /*return !ingredient.isCustom();
        *///?} else {
        return true;
        //?}
    }

    public static ItemStack[] items(Ingredient ingredient) {
        //? if >=26.1 {
        /*// Item-only compaction cannot preserve a custom ingredient's predicate or components.
        // Let the caller retain its original recipes instead of approximating that predicate.
        if (!canFlatten(ingredient)) {
            throw new IllegalArgumentException("Custom ingredient cannot be compacted into item alternatives");
        }
        return ingredient.items().map(ItemStack::new).toArray(ItemStack[]::new);
        *///?} else {
        return ingredient.getItems();
        //?}
    }

    public static Ingredient ofStacks(Stream<ItemStack> stacks) {
        //? if >=26.1 {
        /*return Ingredient.of(stacks.map(ItemStack::getItem));
        *///?} else {
        return Ingredient.of(stacks);
        //?}
    }

    public static List<Ingredient> ingredients(Recipe<?> recipe) {
        //? if >=26.1 {
        /*return recipe.placementInfo().ingredients();
        *///?} else {
        return recipe.getIngredients();
        //?}
    }
}
