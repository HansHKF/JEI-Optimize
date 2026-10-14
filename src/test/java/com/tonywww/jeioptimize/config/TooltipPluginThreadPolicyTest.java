package com.tonywww.jeioptimize.config;

public final class TooltipPluginThreadPolicyTest {
    public static void main(String[] args) {
        check(JeiMainThreadPluginPolicy.requiresMainThread("enderio:base", "Registering recipes", true),
            "runtime ingredient removal must use the client thread with an existing config");
        check(JeiMainThreadPluginPolicy.requiresMainThread("enderio:base", "Registering recipes", false),
            "runtime ingredient removal also requires the client thread on older Minecraft");
        check(JeiMainThreadPluginPolicy.requiresMainThread("occultism:jei", "Registering recipes", true),
            "ritual text layout must be allowed to upload glyphs on Minecraft 26.1+");
        check(!JeiMainThreadPluginPolicy.requiresMainThread("occultism:jei", "Registering recipes", false),
            "older font APIs do not require the new ritual recipe routing");
        check(JeiMainThreadPluginPolicy.requiresMainThread("example:plugin", "Registering categories", true),
            "category constructors can upload glyphs before recipe registration");
        check(!JeiMainThreadPluginPolicy.requiresMainThread("example:plugin", "Registering categories", false),
            "older category registration stays eligible for background execution");
        check(!JeiMainThreadPluginPolicy.requiresMainThread("example:plugin", "Registering recipes", true),
            "ordinary recipe plugins retain background execution");
        check(!JeiMainThreadPluginPolicy.requiresMainThread("enderio:machines", "Registering recipes", true),
            "only the callback that mutates runtime ingredients requires routing");
        check(!JeiMainThreadPluginPolicy.requiresMainThread("occultism:jei", "Registering ingredients", true),
            "ritual recipe routing does not move unrelated registration phases");
        check(JeiMainThreadPluginPolicy.requiresMainThread("jei:minecraft", "Registering ingredients", false),
            "creative tab enumeration retains its client thread requirement");
        for (String gui : new String[]{"jei:forge_gui", "jei:neoforge_gui"}) {
            check(JeiMainThreadPluginPolicy.requiresMainThread(gui, "Registering Runtime", false),
                "GUI runtime construction retains its client thread requirement");
            check(!JeiMainThreadPluginPolicy.requiresMainThread(gui, "Registering recipes", true),
                "GUI routing is limited to runtime construction");
        }
        check(JeiMainThreadPluginPolicy.requiresMainThread(null, "Registering recipes", false),
            "plugins with an unreadable ID retain the conservative route");
        check(JeiMainThreadPluginPolicy.requiresMainThread("blue_skies:jei_plugin", "Registering recipes", false),
            "existing built-in plugin routing is preserved");
        check(JeiMainThreadPluginPolicy.requiresMainThread("delightful:jei_plugin", "Registering recipes", false),
            "existing built-in plugin routing is preserved");
        System.out.println("TooltipPluginThreadPolicyTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
