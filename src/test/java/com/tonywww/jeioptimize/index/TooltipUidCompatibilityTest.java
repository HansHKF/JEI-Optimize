package com.tonywww.jeioptimize.index;

import com.tonywww.jeioptimize.recipe.JeiIngredientUid;
import mezz.jei.api.ingredients.IIngredientHelper;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TooltipUidCompatibilityTest {
    public static void main(String[] arguments) {
        @SuppressWarnings("unchecked")
        IIngredientHelper<String> helper = (IIngredientHelper<String>) Proxy.newProxyInstance(
            IIngredientHelper.class.getClassLoader(), new Class<?>[]{IIngredientHelper.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getUid", "getGroupingUid" -> new Uid((String) args[0]);
                    case "getUniqueId", "getWildcardId" -> args[0];
                    default -> throw new AssertionError("Unexpected helper call: " + method);
                };
            });
        Object first = JeiIngredientUid.recipe(helper, "first");
        Object second = JeiIngredientUid.recipe(helper, "second");
        Map<Object, String> compacted = new HashMap<>();
        compacted.put(first, "first");
        compacted.put(second, "second");
        compacted.put(JeiIngredientUid.recipe(helper, "first"), "first-again");
        check(compacted.size() == 2, "unequal UIDs retained; equal UIDs merged");
        check(!List.of(first, second).equals(List.of(second, first)), "pair identity preserves slot order");
        check(JeiIngredientUid.grouping(helper, "first").equals(JeiIngredientUid.grouping(helper, "first")),
            "grouping UID retains equality");
        if (first instanceof Uid) {
            check(first.toString().equals(second.toString()) && !first.equals(second), "colliding display text fixture");
        }
        System.out.println("TooltipUidCompatibilityTest passed: UID equality, display collisions, ordered pairs");
    }

    private record Uid(String identity) {
        @Override public String toString() { return "same display text"; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
