package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.recipe.JeiRecipeGenerationLimiter;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker$EnchantmentData", remap = false)
public abstract class AnvilEnchantmentRepresentativeMixin {
    @Inject(
        //? if >=26.1 {
        /*method = "canEnchant(Lmezz/jei/common/platform/IPlatformItemStackHelper;Lnet/minecraft/world/item/ItemStack;)Z",
        *///?} else {
        method = "canEnchant(Lnet/minecraft/world/item/ItemStack;)Z",
        //?}
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void jeiOptimize$limitCompatibleItems(
        //? if >=26.1 {
        /*mezz.jei.common.platform.IPlatformItemStackHelper platformHelper,
        *///?}
        ItemStack ingredient,
        CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (Boolean.TRUE.equals(callbackInfo.getReturnValue())
            && !JeiRecipeGenerationLimiter.shouldKeepAnvil(this, ingredient)) {
            callbackInfo.setReturnValue(false);
        }
    }
}