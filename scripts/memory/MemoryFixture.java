package com.tonywww.jetmemoryfixture;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.Mod;
import java.util.ArrayList;
import java.util.List;

/** Development-only fixture: never part of a JET release. */
@Mod("jet_memory_fixture")
@JeiPlugin
public final class MemoryFixture implements IModPlugin {
    @Override public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("jet_memory_fixture", "bulk_visibility");
    }
    @Override public void onRuntimeAvailable(IJeiRuntime runtime) {
        var manager=runtime.getIngredientManager();
        var filter=runtime.getIngredientFilter();
        String original=filter.getFilterText();
        filter.setFilterText("");
        int before=filter.getFilteredIngredients(VanillaTypes.ITEM_STACK).size();
        List<ItemStack> all=new ArrayList<>(filter.getFilteredIngredients(VanillaTypes.ITEM_STACK));
        List<ItemStack> changed=List.copyOf(all.subList(0,Math.min(512,all.size())));
        manager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK,changed);
        int hidden=filter.getFilteredIngredients(VanillaTypes.ITEM_STACK).size();
        manager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK,changed);
        int restored=filter.getFilteredIngredients(VanillaTypes.ITEM_STACK).size();
        if(hidden>=before || restored!=before) throw new IllegalStateException("Visibility mismatch: "+before+" / "+hidden+" / "+restored);
        filter.setFilterText(original);
        com.mojang.logging.LogUtils.getLogger().info("JET memory fixture passed: changed={}, before={}, hidden={}, restored={}",changed.size(),before,hidden,restored);
    }
}
