//? if >=26.1 {
/*package com.tonywww.jeioptimize.mixin;

import com.tonywww.jeioptimize.index.DeferredNativeSearchStorage;
import com.tonywww.jeioptimize.config.JeiOptFeatureFlags;
import com.tonywww.jeioptimize.index.TooltipSearchAdapter;
import com.tonywww.jeioptimize.runtime.JeiOptFilterBootstrap;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.IElementSearch;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.IngredientFilter", remap = false)
public abstract class IngredientFilter26Mixin {
    private static final String CONSTRUCTOR = "<init>(Lmezz/jei/gui/filter/IFilterTextSource;"
        + "Lmezz/jei/common/config/IClientConfig;Lmezz/jei/common/config/IIngredientFilterConfig;"
        + "Lmezz/jei/api/runtime/IIngredientManager;Ljava/util/function/Function;Ljava/util/List;"
        + "Lmezz/jei/api/helpers/IModIdHelper;Lmezz/jei/api/runtime/IIngredientVisibility;"
        + "Lmezz/jei/gui/config/IngredientTypeSortingConfig;Lmezz/jei/api/helpers/IColorHelper;"
        + "Lmezz/jei/api/search/ISearchStorageBuilderFactory;Lmezz/jei/common/config/IClientToggleState;)V";

    @Shadow private IElementSearch elementSearch;
    @Shadow @Final private IIngredientVisibility ingredientVisibility;
    @Shadow @Final private ElementPrefixParser elementPrefixParser;
    @Shadow @Final private IClientConfig clientConfig;
    @Shadow public abstract void invalidateCache();
    @Shadow public abstract void rebuildItemFilter();
    @Invoker("notifyListenersOfChange") protected abstract void jeiopt$notifyReady();

    @Invoker("createElementSearch")
    private static IElementSearch jeiopt$createSearch(IClientConfig config, ElementPrefixParser parser,
        List<IListElementInfo<?>> infos, IIngredientManager manager) {
        throw new AssertionError("replaced by mixin");
    }

    @Redirect(method = CONSTRUCTOR, at = @At(value = "INVOKE",
        target = "Lmezz/jei/gui/ingredients/IngredientFilter;createElementSearch("
            + "Lmezz/jei/common/config/IClientConfig;Lmezz/jei/gui/search/ElementPrefixParser;"
            + "Ljava/util/List;Lmezz/jei/api/runtime/IIngredientManager;)Lmezz/jei/gui/search/IElementSearch;"),
        require = 1)
    private IElementSearch jeiopt$deferSearch(IClientConfig config, ElementPrefixParser parser,
        List<IListElementInfo<?>> infos, IIngredientManager manager) {
        if (!JeiOptFilterBootstrap.canDeferFilter() || TooltipSearchAdapter.lowMemory(config)) {
            return jeiopt$createSearch(config, parser, infos, manager);
        }
        if (!JeiOptFilterBootstrap.canDeferTooltip() && !JeiOptFeatureFlags.tooltipSearchMetrics()) {
            var captured = DeferredNativeSearchStorage.capture(() -> jeiopt$createSearch(config, parser, List.of(), manager));
            JeiOptFilterBootstrap.capture(infos, manager, captured.value(), captured.storages());
            return captured.value();
        }
        IElementSearch empty = jeiopt$createSearch(config, parser, List.of(), manager);
        JeiOptFilterBootstrap.capture(infos, manager, empty);
        return empty;
    }

    @Inject(method = CONSTRUCTOR, at = @At("RETURN"), require = 1)
    private void jeiopt$scheduleSearch(CallbackInfo callback) {
        JeiOptFilterBootstrap.Pending pending = JeiOptFilterBootstrap.take();
        if (pending == null) { return; }
        Runnable refresh = () -> { invalidateCache(); jeiopt$notifyReady(); };
        if (JeiOptFilterBootstrap.canDeferTooltip()) {
            JeiOptFilterBootstrap.scheduleTooltip(pending.ingredients(), pending.ingredientManager(),
                ingredientVisibility, elementPrefixParser, pending.emptySearch(),
                () -> jeiopt$createSearch(clientConfig, elementPrefixParser, List.of(), pending.ingredientManager()),
                () -> jeiopt$createSearch(clientConfig, elementPrefixParser, pending.ingredients(), pending.ingredientManager()),
                search -> elementSearch = search, this::rebuildItemFilter, refresh);
        } else {
            JeiOptFilterBootstrap.scheduleNative(pending.ingredients(), pending.ingredientManager(),
                ingredientVisibility, elementPrefixParser, pending.emptySearch(), search -> elementSearch = search,
                this::rebuildItemFilter, refresh, pending.storages());
        }
    }
}
*///?}
