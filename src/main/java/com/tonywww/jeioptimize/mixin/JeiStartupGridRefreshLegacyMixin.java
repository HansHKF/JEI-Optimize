package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.runtime.JeiOptUiRefreshBatch;
import mezz.jei.common.config.IClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mezz.jei.gui.overlay.IngredientGridWithNavigation", remap = false)
public abstract class JeiStartupGridRefreshLegacyMixin {
    @Invoker("lambda$new$0")
    protected abstract void jeiopt$refreshGrid(IClientConfig config);

    @Inject(method = "lambda$new$0(Lmezz/jei/common/config/IClientConfig;)V",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void jeiopt$deferStartupLayout(IClientConfig config, CallbackInfo callback) {
        if (JeiOptUiRefreshBatch.defer(this, () -> jeiopt$refreshGrid(config))) {
            callback.cancel();
        }
    }
}
