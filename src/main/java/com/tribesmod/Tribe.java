package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Tribe {
    public static final String[] TRAITS = {"Warlike", "Merchant", "Industrious"};

    public int id;
    public String name = "Tribe";
    public BlockPos center = BlockPos.ZERO;
    public int food = 20, wood = 0, wealth = 0, level = 1, pop = 4, slots = 0;
    public int stone = 0, iron = 0, gold = 0, diamond = 0, blaze = 0, pearls = 0;
    /** stage: 0 stone, 1 iron, 2 diamond, 3 nether, 4 stronghold, 5 end siege, 6 empire */
    public int stage = 0, parent = 0, trait = 0, cool = 0, tries = 0;
    public boolean founded = true, portalBuilt = false, monument = false, wellBuilt = false, walled = false;
    /** Construction site: bType -1 = none, otherwise a Blueprints type being built block by block. */
    public int bType = -1, bx, by, bz, bProg, bIdle, built;
    /** Army / expedition orders (game time in ticks). */
    public int raidTarget = 0;
    public long raidUntil = 0, expUntil = 0, expCool = 0;
    /** What the tribe has learned: 16x16 areas where people got hurt or died (score decays slowly). */
    public final Map<Long, Integer> danger = new HashMap<>();
    /** More deaths from monsters/enemies = the tribe trains more warriors and archers. */
    public int threat = 0;
    /** Village alarm (not saved): someone was attacked near the village. */
    public long alarmUntil = 0;
    public int alarmX, alarmZ;
    // ---- v3.3: storehouse, castle, jail, big farms, wars ----
    public boolean hasStore, castle, castleDone, hasJail;
    public int sx, sy, sz, cx, cy, cz, jx, jy, jz, bigFarms;
    public long warCool, warUntil;
    /** How many of my people each other tribe has killed (fuel for revenge wars). */
    public final Map<Integer, Integer> grievance = new HashMap<>();
    /** What the tribe needs right now (not saved, recomputed every 5 seconds). */
    public final double[] needs = new double[TribeBrain.N];
    public final String[] needWhy = new String[TribeBrain.N];
    { java.util.Arrays.fill(needWhy, ""); }
    public int penCount;
    public int[] mine = new int[TribeMine.SIZE];
    public Set<Integer> enemies = new HashSet<>();

    static long cell(int x, int z) { return ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL); }

    public void markDanger(BlockPos p, int n) {
        long k = cell(p.getX(), p.getZ());
        danger.merge(k, n, Integer::sum);
        if (danger.get(k) > 20) danger.put(k, 20);
        if (danger.size() > 64) {
            long weakest = k;
            int low = Integer.MAX_VALUE;
            for (Map.Entry<Long, Integer> e : danger.entrySet()) {
                if (e.getValue() < low) { low = e.getValue(); weakest = e.getKey(); }
            }
            danger.remove(weakest);
        }
    }

    public int dangerAt(BlockPos p) { return danger.getOrDefault(cell(p.getX(), p.getZ()), 0); }

    /** Workers keep away from areas with a score of 4 or more. */
    public boolean unsafe(BlockPos p) { return dangerAt(p) >= 4; }

    /** The memory fades: every call forgets a little. */
    public void decay() {
        danger.replaceAll((k, v) -> v - 1);
        danger.values().removeIf(v -> v <= 0);
        if (threat > 0) threat--;
    }

    public int maxLevel() { return 8 + 2 * stage; }
    public int popCap() { return 8 + 6 * level; }
    public int entityCap() { return Math.min(TribeConfig.MAX_ENTITIES, 12 + 3 * level); }
    public int territory() { return 30 + 3 * level + (level >= 4 ? 20 : 0) + (castle ? 25 : 0); }
    public int tier() { return stage >= 3 ? 3 : stage >= 2 ? 2 : stage >= 1 ? 1 : 0; }

    public double strength() {
        double s = pop + level * 3 + wealth / 40.0 + stage * 10;
        if (trait == 0) s *= 1.15;
        return s;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", id);
        t.putString("name", name);
        t.putInt("x", center.getX());
        t.putInt("y", center.getY());
        t.putInt("z", center.getZ());
        t.putInt("food", food);
        t.putInt("wood", wood);
        t.putInt("wealth", wealth);
        t.putInt("level", level);
        t.putInt("pop", pop);
        t.putInt("slots", slots);
        t.putInt("stone", stone);
        t.putInt("iron", iron);
        t.putInt("gold", gold);
        t.putInt("diamond", diamond);
        t.putInt("blaze", blaze);
        t.putInt("pearls", pearls);
        t.putInt("stage", stage);
        t.putInt("parent", parent);
        t.putInt("trait", trait);
        t.putInt("cool", cool);
        t.putInt("tries", tries);
        t.putBoolean("founded", founded);
        t.putBoolean("portal", portalBuilt);
        t.putBoolean("monument", monument);
        t.putBoolean("well", wellBuilt);
        t.putBoolean("walled", walled);
        t.putInt("raidt", raidTarget);
        t.putLong("raidu", raidUntil);
        t.putLong("expu", expUntil);
        t.putLong("expc", expCool);
        t.putInt("btype", bType);
        t.putInt("bx", bx);
        t.putInt("by", by);
        t.putInt("bz", bz);
        t.putInt("bprog", bProg);
        t.putInt("bidle", bIdle);
        t.putInt("built", built);
        long[] dk = new long[danger.size()];
        int[] dv = new int[danger.size()];
        int di = 0;
        for (Map.Entry<Long, Integer> e : danger.entrySet()) {
            dk[di] = e.getKey();
            dv[di++] = e.getValue();
        }
        t.putBoolean("hasstore", hasStore);
        t.putBoolean("castle", castle);
        t.putBoolean("castledone", castleDone);
        t.putBoolean("hasjail", hasJail);
        t.putIntArray("pos", new int[]{sx, sy, sz, cx, cy, cz, jx, jy, jz, bigFarms});
        t.putLong("warcool", warCool);
        t.putLong("waruntil", warUntil);
        int[] gk = new int[grievance.size()];
        int[] gv = new int[grievance.size()];
        int gi = 0;
        for (Map.Entry<Integer, Integer> e : grievance.entrySet()) {
            gk[gi] = e.getKey();
            gv[gi++] = e.getValue();
        }
        t.putIntArray("gk", gk);
        t.putIntArray("gv", gv);
        t.putLongArray("dk", dk);
        t.putIntArray("dv", dv);
        t.putInt("threat", threat);
        t.putIntArray("mine", mine);
        t.putIntArray("enemies", new ArrayList<>(enemies));
        return t;
    }

    public static Tribe load(CompoundTag t) {
        Tribe r = new Tribe();
        r.id = t.getInt("id");
        r.name = t.getString("name");
        r.center = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
        r.food = t.getInt("food");
        r.wood = t.getInt("wood");
        r.wealth = t.getInt("wealth");
        r.level = Math.max(1, t.getInt("level"));
        r.pop = t.getInt("pop");
        r.slots = t.getInt("slots");
        r.stone = t.getInt("stone");
        r.iron = t.getInt("iron");
        r.gold = t.getInt("gold");
        r.diamond = t.getInt("diamond");
        r.blaze = t.getInt("blaze");
        r.pearls = t.getInt("pearls");
        r.stage = t.getInt("stage");
        r.parent = t.getInt("parent");
        r.trait = t.contains("trait") ? t.getInt("trait") : Math.floorMod(r.id, 3);
        r.cool = t.getInt("cool");
        r.tries = t.getInt("tries");
        r.founded = !t.contains("founded") || t.getBoolean("founded");
        r.portalBuilt = t.getBoolean("portal");
        r.monument = t.getBoolean("monument");
        r.wellBuilt = t.getBoolean("well");
        r.walled = t.getBoolean("walled");
        r.raidTarget = t.getInt("raidt");
        r.raidUntil = t.getLong("raidu");
        r.expUntil = t.getLong("expu");
        r.expCool = t.getLong("expc");
        r.bType = t.contains("btype") ? t.getInt("btype") : -1;
        r.bx = t.getInt("bx");
        r.by = t.getInt("by");
        r.bz = t.getInt("bz");
        r.bProg = t.getInt("bprog");
        r.bIdle = t.getInt("bidle");
        r.built = t.getInt("built");
        long[] dk = t.getLongArray("dk");
        int[] dv = t.getIntArray("dv");
        for (int i = 0; i < Math.min(dk.length, dv.length); i++) r.danger.put(dk[i], dv[i]);
        r.threat = t.getInt("threat");
        r.hasStore = t.getBoolean("hasstore");
        r.castle = t.getBoolean("castle");
        r.castleDone = t.getBoolean("castledone");
        r.hasJail = t.getBoolean("hasjail");
        int[] ps = t.getIntArray("pos");
        if (ps.length == 10) {
            r.sx = ps[0]; r.sy = ps[1]; r.sz = ps[2];
            r.cx = ps[3]; r.cy = ps[4]; r.cz = ps[5];
            r.jx = ps[6]; r.jy = ps[7]; r.jz = ps[8];
            r.bigFarms = ps[9];
        }
        r.warCool = t.getLong("warcool");
        r.warUntil = t.getLong("waruntil");
        int[] gk = t.getIntArray("gk");
        int[] gv = t.getIntArray("gv");
        for (int i = 0; i < Math.min(gk.length, gv.length); i++) r.grievance.put(gk[i], gv[i]);
        int[] m = t.getIntArray("mine");
        if (m.length == TribeMine.SIZE) r.mine = m;
        for (int e : t.getIntArray("enemies")) r.enemies.add(e);
        return r;
    }
}
