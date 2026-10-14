package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.config.JeiOptFeatureFlags;
import com.tonywww.jeioptimize.recipe.JeiRecipeGenerationLimiter;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.runtime.IIngredientManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker", remap = false)
public abstract class AnvilRepresentativeContextMixin {
    @Inject(
        method = "getAnvilRecipes(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lmezz/jei/api/runtime/IIngredientManager;"
        //? if >=26.1 {
        /*+ "Lnet/minecraft/util/context/ContextMap;"
        *///?}
        + ")Ljava/util/List;",
        at = @At("HEAD"),
        require = 1
    )
    private static void jeiOptimize$beginRepresentativeSelection(
        IVanillaRecipeFactory vanillaRecipeFactory,
        IIngredientManager ingredientManager,
        //? if >=26.1 {
        /*net.minecraft.util.context.ContextMap contextMap,
        *///?}
        CallbackInfoReturnable<List<?>> callbackInfo
    ) {
        if (JeiOptFeatureFlags.optimizeAnvilRepresentatives()
            && !JeiOptFeatureFlags.disableAnvilEnchantRecipes()) {
            JeiRecipeGenerationLimiter.beginAnvil(
                ingredientManager,
                JeiOptFeatureFlags.anvilRepresentativesPerEnchantment()
            );
        } else {
            JeiRecipeGenerationLimiter.clearAnvil();
        }
    }

    @Inject(
        method = "getAnvilRecipes(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lmezz/jei/api/runtime/IIngredientManager;"
        //? if >=26.1 {
        /*+ "Lnet/minecraft/util/context/ContextMap;"
        *///?}
        + ")Ljava/util/List;",
        at = @At("RETURN"),
        require = 1
    )
    private static void jeiOptimize$endRepresentativeSelection(
        IVanillaRecipeFactory vanillaRecipeFactory,
        IIngredientManager ingredientManager,
        //? if >=26.1 {
        /*net.minecraft.util.context.ContextMap contextMap,
        *///?}
        CallbackInfoReturnable<List<?>> callbackInfo
    ) {
        JeiRecipeGenerationLimiter.endAnvil();
    }
}