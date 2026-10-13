package com.tonywww.jeioptimize;

import com.tonywww.jeioptimize.runtime.CategoryVisibilityPrep;

import java.util.List;

public final class TooltipCategoryVisibilityOrderTest {
    public static void main(String[] arguments) {
        List<String> categories = List.of("a", "b", "c", "d");
        List<String> visible = CategoryVisibilityPrep.visibleInOrder(categories, category -> "b".equals(category) || "d".equals(category));
        if (!List.of("a", "c").equals(visible)) {
            throw new AssertionError("visible categories must keep source order, got " + visible);
        }
        if (!CategoryVisibilityPrep.visibleInOrder(List.of(), category -> false).isEmpty()) {
            throw new AssertionError("empty category list stays empty");
        }
        System.out.println("TooltipCategoryVisibilityOrderTest passed");
    }
}
