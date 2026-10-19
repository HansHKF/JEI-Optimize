package com.tonywww.jeioptimize.recipe;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;

/** Keep JEI's UID equality, including object UIDs on JEI 29 and newer. */
public final class JeiIngredientUid {
    private JeiIngredientUid() { }

    public static <T> Object recipe(IIngredientHelper<T> helper, T ingredient) {
        //? if >=26.1 {
        /*return helper.getUid(ingredient, UidContext.Recipe);
        *///?} else {
        return helper.getUniqueId(ingredient, UidContext.Recipe);
        //?}
    }

    public static <T> Object grouping(IIngredientHelper<T> helper, T ingredient) {
        //? if >=26.1 {
        /*return helper.getGroupingUid(ingredient);
        *///?} else {
        return helper.getWildcardId(ingredient);
        //?}
    }
}
