//? if >=26.1 {
/*package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.runtime.JeiOptStartupProgressState;
import com.tonywww.jeioptimize.mixin.accessor.JeiRuntimeAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mezz.jei.gui.events.GuiEventHandler", remap = false)
public abstract class JeiGuiRenderGuard26Mixin {
    @Inject(method = {
        "onGuiInit(Lnet/minecraft/client/gui/screens/Screen;)V",
        "onGuiOpen(Lnet/minecraft/client/gui/screens/Screen;)V",
        "updateForScreenRender(Lnet/minecraft/client/gui/screens/Screen;II)V",
        "drawForScreenBackground(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;)V",
        "drawForScreenForeground(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;II)V"
    }, at = @At("HEAD"), cancellable = true, require = 5)
    private void jeiopt$waitForRuntime(CallbackInfo callback) {
        if (JeiOptStartupProgressState.blocksJeiRendering(JeiRuntimeAccessor.jeiopt$getNullableRuntime() != null)) {
            callback.cancel();
        }
    }
}
*///?}
