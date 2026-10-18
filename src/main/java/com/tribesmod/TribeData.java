package com.tribesmod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class TribeData extends SavedData {
    public final Map<Integer, Tribe> tribes = new LinkedHashMap<>();
    public int nextId = 1;
    public int dragonsSlain = 0;
    public int pendingDragons = 0;

    private static final SavedData.Factory<TribeData> FACTORY =
            new SavedData.Factory<>(TribeData::new, TribeData::load, null);

    public static TribeData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "tribes_data");
    }

    public static TribeData load(CompoundTag tag, HolderLookup.Provider reg) {
        TribeData d = new TribeData();
        d.nextId = Math.max(1, tag.getInt("next"));
        d.dragonsSlain = tag.getInt("slain");
        d.pendingDragons = tag.getInt("pending");
        ListTag list = tag.getList("tribes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Tribe t = Tribe.load(list.getCompound(i));
            d.tribes.put(t.id, t);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (Tribe t : tribes.values()) list.add(t.save());
        tag.put("tribes", list);
        tag.putInt("next", nextId);
        tag.putInt("slain", dragonsSlain);
        tag.putInt("pending", pendingDragons);
        return tag;
    }
}
