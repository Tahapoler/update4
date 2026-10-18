package com.tribesmod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Turns real items that a tribesman picks up into tribe resources. */
public final class TribeItems {
    private TribeItems() {}

    public static void credit(Tribe t, ItemStack s) {
        int n = s.getCount();
        Item i = s.getItem();
        if (s.is(ItemTags.LOGS)) t.wood += 4 * n;
        else if (s.is(ItemTags.PLANKS)) t.wood += n;
        else if (i == Items.STICK || s.is(ItemTags.SAPLINGS)) t.wood += Math.max(1, n / 2);
        else if (i == Items.WHEAT) t.food += 3 * n;
        else if (i == Items.WHEAT_SEEDS || i == Items.BEETROOT_SEEDS || i == Items.MELON_SEEDS || i == Items.PUMPKIN_SEEDS) t.food += 0;
        else if (i == Items.COBBLESTONE || i == Items.COBBLED_DEEPSLATE || i == Items.STONE || i == Items.DEEPSLATE
                || i == Items.DIRT || i == Items.GRAVEL || i == Items.ANDESITE || i == Items.DIORITE || i == Items.GRANITE) t.stone += n;
        else if (i == Items.IRON_INGOT) t.iron += 3 * n;
        else if (i == Items.RAW_IRON || i == Items.IRON_NUGGET) t.iron += n;
        else if (i == Items.GOLD_INGOT || i == Items.RAW_GOLD) t.gold += 2 * n;
        else if (i == Items.DIAMOND) t.diamond += 3 * n;
        else if (i == Items.EMERALD) t.wealth += 25 * n;
        else if (i == Items.BLAZE_ROD) t.blaze += n;
        else if (i == Items.ENDER_PEARL) t.pearls += n;
        else if (s.is(ItemTags.COALS)) t.wealth += 3 * n;
        else {
            FoodProperties f = s.get(DataComponents.FOOD);
            if (f != null) t.food += Math.max(1, f.nutrition()) * n;
            else t.wealth += n;
        }
    }
}
