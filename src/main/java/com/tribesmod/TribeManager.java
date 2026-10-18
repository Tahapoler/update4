package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class TribeManager {
    static final String[] P1 = {"Stone", "Iron", "Red", "Black", "Wolf", "Raven", "Storm", "Ember", "Frost", "Gold", "Oak", "Shadow", "Sun", "Bone"};
    static final String[] P2 = {"fang", "clan", "hold", "born", "wood", "guard", "hawk", "blade", "hollow", "men", "river", "peak"};
    static final int ROLES = 7; // farmer, woodcutter, trader, warrior, miner, builder, archer
    static final double[] WEIGHTS = {4, 2, 2.5, 1.5, 2.5, 2, 1.3};

    // ================= main loop (every 5 seconds) =================
    public static void tick(ServerLevel level) {
        TribeData data = TribeData.get(level);
        RandomSource rnd = level.random;
        if (data.tribes.size() < TribeConfig.MAX_TRIBES) {
            for (ServerPlayer p : level.players()) {
                if (rnd.nextInt(2) == 0) tryFound(level, data, p.blockPosition(), rnd);
            }
        }
        for (Tribe t : new ArrayList<>(data.tribes.values())) tickTribe(level, data, t, rnd);
        data.setDirty();
    }

    static void tickTribe(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        if (!data.tribes.containsKey(t.id)) return;

        // vassals share the technology of their overlord
        if (t.parent != 0) {
            Tribe p = data.tribes.get(t.parent);
            if (p == null) t.parent = 0;
            else if (p.stage > t.stage) t.stage = p.stage;
        }

        boolean near = isNear(level, t);
        if (level.getGameTime() % 600 == 0) t.decay(); // the tribe slowly forgets old dangers

        // a colony that has no buildings yet: settle it as soon as its land is loaded
        if (!t.founded) {
            trySettle(level, data, t, rnd);
            if (!data.tribes.containsKey(t.id)) return;
            if (!t.founded) near = false;
        }

        if (near) {
            List<TribesmanEntity> mem = members(level, t);
            if (mem.size() > t.pop) t.pop = mem.size();
            if (t.pop <= 0) { removeTribe(data, t); return; }
            int[] c = new int[ROLES];
            for (TribesmanEntity m : mem) c[m.getRole()]++;
            int want = Math.min(t.pop, t.entityCap()) - mem.size();
            int spawnMax = 3 + t.level / 4;
            // an open construction site always gets a builder first
            if (t.bType >= 0 && c[TribesmanEntity.BUILDER] == 0 && want > 0) {
                spawnMember(level, t, TribesmanEntity.BUILDER);
                c[TribesmanEntity.BUILDER]++;
                want--;
            }
            for (int i = 0; i < Math.min(want, spawnMax); i++) spawnMember(level, t, pickRole(c, t));
            manageBuilding(level, t, c[TribesmanEntity.BUILDER]);
            specials(level, t);
            refreshStore(level, t);
            refreshTreasury(level, t);
            t.penCount = pens(level, t).size();
            TribeBrain.assess(t, level.getGameTime());
            manageWar(level, data, t, c[TribesmanEntity.WARRIOR] + c[TribesmanEntity.ARCHER]);
            manageJail(level, data, t, rnd);
        } else if (t.pop <= 0) {
            removeTribe(data, t);
            return;
        }

        // ---- economy ----
        double g = TribeConfig.GROWTH;
        double v = near ? 0.35 : 1.0;
        t.food += (int) Math.ceil((1 + t.pop * 0.5) * v * g);
        t.wood += (int) Math.ceil(Math.max(1, t.pop / 2.0) * v * g * 0.7);
        t.food = Math.max(0, t.food - t.pop / 3);
        int births = 1 + t.pop / 20;
        for (int i = 0; i < births; i++) {
            if (t.food >= 40 && t.pop < t.popCap()) { t.food -= 40; t.pop++; }
        }
        int tax = Math.max(1, t.pop / 6);
        if (t.trait == 1) tax = tax * 3 / 2;
        t.wealth += (int) Math.ceil(tax * g);
        if (t.food > 150) { t.food -= 100; t.wealth += 20; }
        if (t.wood > 200 && t.slots >= 4 + 2 * t.level) { t.wood -= 100; t.wealth += 10; }
        if (t.parent != 0) { // vassals pay tribute to the empire
            Tribe p = data.tribes.get(t.parent);
            if (p != null) {
                int tribute = t.wealth / 10;
                t.wealth -= tribute;
                p.wealth += tribute;
            }
        }
        if (t.level < t.maxLevel() && t.wealth >= 50 * t.level && t.pop >= 4 + 3 * t.level) {
            t.wealth -= 50 * t.level;
            t.level++;
            notifyNear(level, t.center, "§6[" + t.name + "] grew to level " + t.level + "!");
        }

        // ---- technology, dragon quest and expansion ----
        TribeProgress.tick(level, data, t, rnd, near);
        TribeProgress.expand(level, data, t, rnd);

        // ---- diplomacy: a tribe only goes to war for a reason ----
        if (rnd.nextInt(6) == 0) {
            Tribe o = nearestOther(data, t, 450 + 20 * t.level);
            if (o != null) {
                long now = level.getGameTime();
                if (t.enemies.contains(o.id)) {
                    if (now > t.warUntil && rnd.nextInt(3) == 0) makePeace(level, t, o);
                } else if (t.level >= 3 && t.warCool < now) {
                    String why = warReason(t, o, data, rnd);
                    if (why != null) declareWar(level, data, t, o, why);
                    else if (rnd.nextInt(t.trait == 1 ? 2 : 3) == 0) trade(t, o);
                } else if (rnd.nextInt(3) == 0) {
                    trade(t, o);
                }
            }
        }

        // ---- simulated war (only when no player is near either tribe) ----
        if (!near) {
            for (int eid : new ArrayList<>(t.enemies)) {
                Tribe o = data.tribes.get(eid);
                if (o == null) { t.enemies.remove(eid); continue; }
                if (t.id < o.id && !isNear(level, o) && rnd.nextInt(3) == 0) skirmish(level, data, t, o, rnd);
            }
        }
    }

    // ================= empires =================
    static int rootId(TribeData data, Tribe t) {
        Tribe c = t;
        for (int i = 0; i < 12 && c.parent != 0; i++) {
            Tribe p = data.tribes.get(c.parent);
            if (p == null) break;
            c = p;
        }
        return c.id;
    }

    static void conquer(ServerLevel level, TribeData data, Tribe w, Tribe l) {
        int root = rootId(data, w);
        if (root == rootId(data, l)) return;
        l.parent = root;
        for (int eid : new ArrayList<>(l.enemies)) {
            Tribe o = data.tribes.get(eid);
            if (o != null) o.enemies.remove(l.id);
        }
        l.enemies.clear();
        w.wealth += 40;
        notifyNear(level, w.center, "§6[EMPIRE] " + w.name + " conquered " + l.name + " - it is now part of the empire!");
    }

    static void trade(Tribe a, Tribe b) {
        a.wealth += 5;
        b.wealth += 5;
        if (a.food > b.food + 30) { a.food -= 10; b.food += 10; }
        else if (b.food > a.food + 30) { b.food -= 10; a.food += 10; }
    }

    static void skirmish(ServerLevel level, TribeData data, Tribe a, Tribe b, RandomSource rnd) {
        double sa = a.strength() * (0.5 + rnd.nextDouble());
        double sb = b.strength() * (0.5 + rnd.nextDouble());
        Tribe w = sa >= sb ? a : b;
        Tribe l = sa >= sb ? b : a;
        boolean overwhelming = w.strength() >= l.strength() * 1.8;
        if (l.pop <= 3 || (overwhelming && rnd.nextInt(2) == 0)) {
            conquer(level, data, w, l);
            makePeace(level, w, l);
            return;
        }
        int loss = Math.max(1, l.pop / 12);
        l.pop = Math.max(1, l.pop - loss);
        w.wealth += 10;
        w.food += 10;
        l.food = Math.max(0, l.food - 10);
    }

    /** The only reasons a tribe goes to war: revenge, famine, a border dispute, or conquest by a mighty tribe. */
    static String warReason(Tribe t, Tribe o, TribeData data, RandomSource rnd) {
        if (rootId(data, t) == rootId(data, o)) return null;
        double st = t.strength(), so = o.strength();
        int g = t.grievance.getOrDefault(o.id, 0);
        if (g >= 5 && st >= so * 0.8) return "revenge for the people " + o.name + " killed";
        if (t.food < t.pop * 2 && o.food > o.pop * 6 && st >= so * 0.9) return "famine - raiding " + o.name + " for food";
        double reach = t.territory() + o.territory() + 10.0;
        if (o.center.distSqr(t.center) < reach * reach && st >= so * 1.3 && rnd.nextInt(4) == 0) {
            return "border dispute over the land between them";
        }
        if (t.level >= 5 && (t.trait == 0 || t.stage >= 6) && st >= so * 2.0 && rnd.nextInt(3) == 0) {
            return "conquest - expanding the empire";
        }
        return null;
    }

    static void link(Tribe a, Tribe b) {
        a.enemies.add(b.id);
        b.enemies.add(a.id);
    }

    static void declareWar(ServerLevel level, TribeData data, Tribe a, Tribe b, String why) {
        long now = level.getGameTime();
        link(a, b);
        a.warUntil = now + 48000;
        b.warUntil = now + 48000;
        a.threat = Math.min(40, a.threat + 6); // both sides start training soldiers
        b.threat = Math.min(40, b.threat + 6);
        for (Tribe v : data.tribes.values()) { // vassals fight with their overlord
            if (v.parent == a.id) link(v, b);
            if (v.parent == b.id) link(v, a);
        }
        TribeLog.add(level, a.name, "declared war on " + b.name + ": " + why);
        notifyNear(level, a.center, "§c[WAR] " + a.name + " declared war on " + b.name + " - " + why + "!");
    }

    static void makePeace(ServerLevel level, Tribe a, Tribe b) {
        long now = level.getGameTime();
        a.warCool = now + 36000;
        b.warCool = now + 36000;
        if (a.raidTarget == b.id) a.raidTarget = 0;
        if (b.raidTarget == a.id) b.raidTarget = 0;
        a.grievance.remove(b.id);
        a.enemies.remove(b.id);
        b.enemies.remove(a.id);
        notifyNear(level, a.center, "§a[PEACE] " + a.name + " and " + b.name + " made peace.");
    }

    static void removeTribe(TribeData data, Tribe t) {
        data.tribes.remove(t.id);
        for (Tribe o : data.tribes.values()) {
            o.enemies.remove(t.id);
            if (o.parent == t.id) o.parent = 0;
        }
    }

    static Tribe nearestOther(TribeData data, Tribe t, double maxDist) {
        Tribe best = null;
        double bd = maxDist * maxDist;
        int myRoot = rootId(data, t);
        for (Tribe o : data.tribes.values()) {
            if (o.id == t.id || rootId(data, o) == myRoot) continue;
            double d = o.center.distSqr(t.center);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    static void notifyNear(ServerLevel level, BlockPos pos, String msg) {
        notifyWithin(level, pos, 400, msg);
    }

    static void notifyWithin(ServerLevel level, BlockPos pos, int radius, String msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(pos) < (double) radius * radius) p.sendSystemMessage(Component.literal(msg));
        }
    }

    static boolean isNear(ServerLevel level, Tribe t) {
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - t.center.getX();
            double dz = p.getZ() - t.center.getZ();
            if (dx * dx + dz * dz < 64.0 * 64.0) return true;
        }
        return false;
    }

    static List<TribesmanEntity> members(ServerLevel level, Tribe t) {
        double r = Math.max(100, t.territory() + 70);
        AABB box = new AABB(t.center.getX() - r, level.getMinBuildHeight(), t.center.getZ() - r,
                t.center.getX() + r, level.getMaxBuildHeight(), t.center.getZ() + r);
        return level.getEntitiesOfClass(TribesmanEntity.class, box, e -> e.getTribeId() == t.id && e.isAlive());
    }

    /** Roles are chosen by weight; a tribe that keeps losing people to monsters trains more fighters. */
    static int pickRole(int[] c, Tribe t) {
        int best = 0;
        double bv = Double.MAX_VALUE;
        double fight = Math.min(4.0, t.threat / 4.0);
        for (int r = 0; r < ROLES; r++) {
            double w = WEIGHTS[r] + (r == TribesmanEntity.WARRIOR ? fight : r == TribesmanEntity.ARCHER ? fight * 0.7 : 0);
            double v = c[r] / w;
            if (v < bv) { bv = v; best = r; }
        }
        c[best]++;
        return best;
    }

    // ================= founding =================
    static void tryFound(ServerLevel level, TribeData data, BlockPos origin, RandomSource rnd) {
        int dist = 40 + rnd.nextInt(45);
        double ang = rnd.nextDouble() * Math.PI * 2;
        int x = origin.getX() + (int) (Math.cos(ang) * dist);
        int z = origin.getZ() + (int) (Math.sin(ang) * dist);
        BlockPos probe = new BlockPos(x, 64, z);
        if (!level.hasChunksAt(probe.offset(-24, 0, -24), probe.offset(24, 0, 24))) return;
        BlockPos pos = new BlockPos(x, groundY(level, x, z), z);
        if (!suitable(level, pos)) return;
        for (Tribe t : data.tribes.values()) {
            if (t.center.distSqr(pos) < (double) TribeConfig.MIN_DIST * TribeConfig.MIN_DIST) return;
        }
        found(level, data, pos);
    }

    static boolean suitable(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (pos.getY() <= level.getSeaLevel()) return false;
        if (!(below.is(BlockTags.DIRT) || below.is(BlockTags.SAND))) return false;
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) return false;
        int[][] corners = {{8, 8}, {-8, 8}, {8, -8}, {-8, -8}};
        for (int[] c : corners) {
            int h = groundY(level, pos.getX() + c[0], pos.getZ() + c[1]);
            if (Math.abs(h - pos.getY()) > 4) return false;
            if (!level.getFluidState(new BlockPos(pos.getX() + c[0], h - 1, pos.getZ() + c[1])).isEmpty()) return false;
        }
        return true;
    }

    public static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (y > level.getMinBuildHeight()) {
            BlockState s = level.getBlockState(new BlockPos(x, y - 1, z));
            if (s.isAir() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) y--;
            else break;
        }
        return y;
    }

    public static Tribe found(ServerLevel level, TribeData data, BlockPos pos) {
        RandomSource rnd = level.random;
        Tribe t = new Tribe();
        t.id = data.nextId++;
        t.name = P1[rnd.nextInt(P1.length)] + P2[rnd.nextInt(P2.length)];
        t.center = pos;
        t.trait = rnd.nextInt(3);
        data.tribes.put(t.id, t);
        build(level, data, t);
        notifyNear(level, pos, "§e[Tribe] A new tribe appeared: " + t.name + " (" + Tribe.TRAITS[t.trait] + ")");
        return t;
    }

    /** Physically builds a tribe's starting village (campfire, well, 4 buildings, first members). */
    static void build(ServerLevel level, TribeData data, Tribe t) {
        BlockPos pos = t.center;
        flatten(level, pos.offset(-7, 0, -7), 15, 15);
        level.setBlock(pos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        if (!t.wellBuilt) {
            Blueprints.applyAll(level, Blueprints.make(Blueprints.WELL, pos.offset(-5, 0, 3), true), 0);
            t.wellBuilt = true;
        }
        for (int i = 0; i < 4; i++) buildSlotInstant(level, t);
        int[] c = new int[ROLES];
        for (int i = 0; i < Math.min(4, Math.max(1, t.pop)); i++) spawnMember(level, t, pickRole(c, t));
        t.founded = true;
        data.setDirty();
    }

    /** A colony founded "on paper": find real ground for it once its chunks are loaded. */
    static void trySettle(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        int cx = t.center.getX(), cz = t.center.getZ();
        if (!level.hasChunksAt(new BlockPos(cx - 40, 64, cz - 40), new BlockPos(cx + 40, 64, cz + 40))) return;
        for (int a = 0; a < 14; a++) {
            int x = cx + (a == 0 ? 0 : rnd.nextInt(61) - 30);
            int z = cz + (a == 0 ? 0 : rnd.nextInt(61) - 30);
            BlockPos pos = new BlockPos(x, groundY(level, x, z), z);
            if (suitable(level, pos)) {
                t.center = pos;
                build(level, data, t);
                notifyNear(level, pos, "§6[EMPIRE] Colony " + t.name + " has been built!");
                return;
            }
        }
        if (++t.tries >= 3) {
            Tribe p = data.tribes.get(t.parent);
            if (p != null) p.pop += Math.max(0, t.pop - 1);
            removeTribe(data, t);
        }
    }

    public static void spawnMember(ServerLevel level, Tribe t, int role) {
        RandomSource r = level.random;
        int x = t.center.getX() + r.nextInt(9) - 4;
        int z = t.center.getZ() + r.nextInt(9) - 4;
        int y = groundY(level, x, z);
        TribesmanEntity e = new TribesmanEntity(TribesMod.TRIBESMAN, level);
        e.moveTo(x + 0.5, y, z + 0.5, r.nextFloat() * 360f, 0f);
        e.setupTribe(t, role);
        if (role == TribesmanEntity.ARCHER) {
            BlockPos post = findPost(level, t, r);
            if (post != null) {
                e.setPost(post);
                e.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, r.nextFloat() * 360f, 0f);
            }
        }
        level.addFreshEntity(e);
    }

    // ================= special buildings =================
    static void specials(ServerLevel level, Tribe t) {
        BlockPos lo = t.center.offset(-12, 0, -12), hi = t.center.offset(12, 0, 12);
        if (TribeConfig.BUILD_PORTAL && t.stage >= 3 && !t.portalBuilt && level.hasChunksAt(lo, hi)) {
            buildPortal(level, t);
            t.portalBuilt = true;
        }
        if (t.stage >= 6 && !t.monument && level.hasChunksAt(lo, hi)) {
            buildMonument(level, t);
            t.monument = true;
        }
    }

    static void buildPortal(ServerLevel level, Tribe t) {
        BlockPos o = t.center.offset(3, 0, 5);
        BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                boolean edge = dx == 0 || dx == 3 || dy == 0 || dy == 4;
                if (edge) level.setBlock(o.offset(dx, dy, 0), obs, 3);
            }
        }
        for (int dx = 1; dx < 3; dx++) {
            for (int dy = 1; dy < 4; dy++) level.setBlock(o.offset(dx, dy, 0), portal, 18);
        }
    }

    static void buildMonument(ServerLevel level, Tribe t) {
        BlockPos b = t.center.offset(-5, 0, -5);
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) level.setBlock(b.offset(dx, 0, dz), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        }
        for (int dy = 1; dy <= 4; dy++) level.setBlock(b.offset(1, dy, 1), Blocks.OBSIDIAN.defaultBlockState(), 3);
        level.setBlock(b.offset(1, 5, 1), Blocks.BEACON.defaultBlockState(), 3);
    }

    // ================= building =================
    /** Position (south-west corner, ground level) of building slot i, or null if the slot is reserved for a mine. */
    static BlockPos slotOrigin(ServerLevel level, Tribe t, int i) {
        double ang = Math.toRadians(i * 60.0 + (i / 6) * 30.0);
        int r = 11 + 7 * (i / 6);
        int cx = t.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int cz = t.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        if (TribeMine.blocks(t, cx, cz)) return null; // keep the mining quarter free
        int y = groundY(level, cx, cz);
        return new BlockPos(cx - 2, y, cz - 2);
    }

    /** Decide whether the tribe should start a new construction site. */
    static void manageBuilding(ServerLevel level, Tribe t, int builders) {
        if (t.bType >= 0) {
            if (builders == 0) t.bIdle++; else t.bIdle = 0;
            if (TribeConfig.INSTANT_FALLBACK && t.bIdle > 36 && !Blueprints.large(t.bType)) finishJobInstantly(level, t);
            return;
        }
        if (TribeConfig.WALLS && !t.walled && t.level >= 4 && t.stone >= 40
                && level.hasChunksAt(t.center.offset(-30, 0, -30), t.center.offset(30, 0, 30))) {
            t.walled = true;
            t.bType = Blueprints.WALL;
            t.bx = t.center.getX();
            t.by = t.center.getY();
            t.bz = t.center.getZ();
            t.bProg = 0;
            t.bIdle = 0;
            TribeLog.add(level, t.name, "started the city wall");
            return;
        }
        if (TribeConfig.CASTLES && !t.castle && t.level >= 5 && t.stone >= 120 && t.wood >= 60 && startCastle(level, t)) return;
        if (TribeConfig.BIG_FARMS && t.level >= 3 && t.bigFarms < Math.min(5, 1 + t.level / 3) && t.wood >= 50
                && startBigFarm(level, t)) return;
        int ext = 18 + 7 * (t.slots / 6) + 6;
        if (t.wood >= 30 && t.slots < 4 + 2 * t.level
                && level.hasChunksAt(t.center.offset(-ext, 0, -ext), t.center.offset(ext, 0, ext))) {
            if (TribeConfig.REALISTIC_BUILDING) startJob(level, t);
            else {
                t.wood -= 30;
                buildSlotInstant(level, t);
            }
        }
    }

    /** The castle stands south of the village with its gate facing the village; 41x41 blocks, flattened first. */
    static boolean startCastle(ServerLevel level, Tribe t) {
        BlockPos o0 = t.center.offset(-20, 0, 38);
        if (!level.hasChunksAt(o0, o0.offset(40, 0, 40))) return false;
        int y = groundY(level, o0.getX() + 20, o0.getZ() + 20);
        if (y <= level.getSeaLevel() + 1) { t.castle = true; return false; } // water: no castle here
        BlockPos o = new BlockPos(o0.getX(), y, o0.getZ());
        t.castle = true;
        flatten(level, o, Blueprints.CASTLE_SIZE, Blueprints.CASTLE_SIZE);
        t.bType = Blueprints.CASTLE;
        t.bx = o.getX();
        t.by = o.getY();
        t.bz = o.getZ();
        t.bProg = 0;
        t.bIdle = 0;
        TribeLog.add(level, t.name, "started building the CASTLE");
        notifyNear(level, t.center, "§6[" + t.name + "] is starting to build a great castle!");
        return true;
    }

    /** Large irrigated farms in a ring around the village. */
    static boolean startBigFarm(ServerLevel level, Tribe t) {
        int[] angles = {10, 150, 210, 270, 350};
        int i = t.bigFarms++;
        double ang = Math.toRadians(angles[i % angles.length]);
        int cx = t.center.getX() + (int) Math.round(Math.cos(ang) * 50);
        int cz = t.center.getZ() + (int) Math.round(Math.sin(ang) * 50);
        BlockPos o0 = new BlockPos(cx - 7, 64, cz - 7);
        if (!level.hasChunksAt(o0, o0.offset(14, 0, 14))) { t.bigFarms--; return false; }
        int[][] pts = {{0, 0}, {14, 0}, {0, 14}, {14, 14}, {7, 7}};
        for (int[] p : pts) {
            if (TribeMine.blocks(t, o0.getX() + p[0], o0.getZ() + p[1])) return false; // keep the mining quarter free
        }
        int y = groundY(level, cx, cz);
        if (y <= level.getSeaLevel() || !level.getFluidState(new BlockPos(cx, y - 1, cz)).isEmpty()) return false;
        BlockPos o = new BlockPos(o0.getX(), y, o0.getZ());
        flatten(level, o, 15, 15);
        t.bType = Blueprints.BIGFARM;
        t.bx = o.getX();
        t.by = o.getY();
        t.bz = o.getZ();
        t.bProg = 0;
        t.bIdle = 0;
        TribeLog.add(level, t.name, "started a large farm");
        return true;
    }

    static void startJob(ServerLevel level, Tribe t) {
        int i = t.slots++;
        BlockPos o = slotOrigin(level, t, i);
        if (o == null) return;
        flatten(level, o, 5, 5);
        t.bType = Blueprints.slotType(i);
        t.bx = o.getX();
        t.by = o.getY();
        t.bz = o.getZ();
        t.bProg = 0;
        t.bIdle = 0;
    }

    /** Called by a builder when the last block of the site has been placed. */
    public static void finishJob(ServerLevel level, Tribe t) {
        if (t.bType < 0) return;
        BlockPos o = new BlockPos(t.bx, t.by, t.bz);
        String what = Blueprints.name(t.bType);
        int type = t.bType;
        t.bType = -1;
        t.bProg = 0;
        t.bIdle = 0;
        t.built++;
        if (type == Blueprints.PEN) populatePen(level, o);
        if (type == Blueprints.STORE) registerStore(t, o);
        if (type == Blueprints.CASTLE) registerCastle(level, t, o);
        if (type != Blueprints.WALL) layPath(level, t.center, o.offset(2, 0, -1));
        TribeLog.add(level, t.name, "finished building: " + what);
        notifyWithin(level, o, 64, "§7[" + t.name + "] finished building a " + what + ".");
        TribeData.get(level).setDirty();
    }

    static void finishJobInstantly(ServerLevel level, Tribe t) {
        BlockPos o = new BlockPos(t.bx, t.by, t.bz);
        List<Blueprints.Placement> list = Blueprints.make(t.bType, o, false);
        Blueprints.applyAll(level, list, Math.max(0, t.bProg));
        finishJob(level, t);
    }

    static void buildSlotInstant(ServerLevel level, Tribe t) {
        int i = t.slots++;
        BlockPos o = slotOrigin(level, t, i);
        if (o == null) return;
        flatten(level, o, 5, 5);
        Blueprints.applyAll(level, Blueprints.make(Blueprints.slotType(i), o, true), 0);
        if (Blueprints.slotType(i) == Blueprints.PEN) populatePen(level, o);
        if (Blueprints.slotType(i) == Blueprints.STORE) registerStore(t, o);
        layPath(level, t.center, o.offset(2, 0, -1));
    }

    /** Simple dirt path from the campfire to a building door (x first, then z). */
    static void layPath(ServerLevel level, BlockPos from, BlockPos to) {
        int x = from.getX(), z = from.getZ();
        int guard = 0;
        while ((x != to.getX() || z != to.getZ()) && guard++ < 80) {
            if (x != to.getX()) x += Integer.signum(to.getX() - x);
            else z += Integer.signum(to.getZ() - z);
            BlockPos probe = new BlockPos(x, 64, z);
            if (!level.hasChunkAt(probe)) return;
            int y = groundY(level, x, z);
            BlockPos g = new BlockPos(x, y - 1, z);
            if (level.getBlockState(g).is(BlockTags.DIRT) && level.getBlockState(g.above()).isAir()) {
                level.setBlock(g, Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
        }
    }

    static void flatten(ServerLevel level, BlockPos o, int w, int d) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    BlockPos p = o.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                BlockPos g = o.offset(dx, -1, dz);
                BlockState cur = level.getBlockState(g);
                if (!(cur.is(BlockTags.DIRT) || cur.is(BlockTags.SAND))) {
                    level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int dy = -2; dy >= -6; dy--) {
                    BlockPos q = o.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(q);
                    if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
                        level.setBlock(q, Blocks.DIRT.defaultBlockState(), 2);
                    } else break;
                }
            }
        }
    }

    // ================= archers, pens, storehouse =================
    /** Guard post on top of one of the tribe's finished watchtowers (or null). */
    static BlockPos findPost(ServerLevel level, Tribe t, RandomSource r) {
        List<BlockPos> posts = new ArrayList<>();
        for (int i = 0; i < t.slots; i++) {
            if (Blueprints.slotType(i) != Blueprints.TOWER) continue;
            BlockPos o = slotOrigin(level, t, i);
            if (o == null || !level.hasChunkAt(o)) continue;
            if (level.getBlockState(o.offset(2, 7, 2)).is(Blocks.STONE_BRICKS)) posts.add(o.offset(3, 8, 2));
        }
        return posts.isEmpty() ? null : posts.get(r.nextInt(posts.size()));
    }

    /** Origins of the tribe's animal pens. */
    static List<BlockPos> pens(ServerLevel level, Tribe t) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < t.slots; i++) {
            if (Blueprints.slotType(i) != Blueprints.PEN) continue;
            BlockPos o = slotOrigin(level, t, i);
            if (o == null || !level.hasChunkAt(o)) continue;
            if (level.getBlockState(o).is(BlockTags.WOODEN_FENCES)) out.add(o);
        }
        return out;
    }

    static void registerStore(Tribe t, BlockPos o) {
        t.hasStore = true;
        t.sx = o.getX();
        t.sy = o.getY();
        t.sz = o.getZ();
    }

    static void registerCastle(ServerLevel level, Tribe t, BlockPos o) {
        t.castle = true;
        t.castleDone = true;
        t.cx = o.getX();
        t.cy = o.getY();
        t.cz = o.getZ();
        t.hasJail = true;
        t.jx = o.getX() + Blueprints.JAIL_X;
        t.jy = o.getY();
        t.jz = o.getZ() + Blueprints.JAIL_Z;
        layPath(level, t.center, o.offset(20, 0, -1)); // the road to the castle gate
        notifyNear(level, t.center, "§6[" + t.name + "] The great castle is finished!");
    }

    static void populatePen(ServerLevel level, BlockPos o) {
        EntityType<? extends Animal> type;
        switch (Math.floorMod(o.getX() + o.getZ(), 4)) {
            case 0 -> type = EntityType.COW;
            case 1 -> type = EntityType.SHEEP;
            case 2 -> type = EntityType.PIG;
            default -> type = EntityType.CHICKEN;
        }
        for (int i = 0; i < 3; i++) {
            Animal a = type.create(level);
            if (a == null) continue;
            a.moveTo(o.getX() + 1.5 + i, o.getY(), o.getZ() + 1.5 + (i % 2), 0f, 0f);
            a.setPersistenceRequired();
            level.addFreshEntity(a);
        }
    }

    private static ItemStack stack(Item item, int n) {
        ItemStack s = new ItemStack(item);
        s.setCount(Math.max(0, Math.min(s.getMaxStackSize(), n)));
        return s;
    }

    /** The storehouse chests mirror the tribe's stock, so you can look inside and see what they own. */
    static void refreshStore(ServerLevel level, Tribe t) {
        for (int i = 0; i < t.slots; i++) {
            if (Blueprints.slotType(i) != Blueprints.STORE) continue;
            BlockPos o = slotOrigin(level, t, i);
            if (o == null || !level.hasChunkAt(o)) continue;
            BlockPos[] cp = {o.offset(1, 0, 3), o.offset(2, 0, 3), o.offset(3, 0, 3), o.offset(1, 0, 1)};
            ItemStack[][] c = {
                    {stack(Items.BREAD, t.food / 6), stack(Items.WHEAT, t.food / 10), stack(Items.COOKED_BEEF, t.food / 20)},
                    {stack(Items.OAK_LOG, t.wood / 4), stack(Items.SPRUCE_PLANKS, t.wood / 2)},
                    {stack(Items.COBBLESTONE, t.stone), stack(Items.IRON_INGOT, t.iron / 3), stack(Items.GOLD_INGOT, t.gold / 2)},
                    {stack(Items.DIAMOND, t.diamond / 3), stack(Items.EMERALD, t.wealth / 200),
                            stack(Items.BLAZE_ROD, t.blaze), stack(Items.ENDER_PEARL, t.pearls)}};
            for (int k = 0; k < cp.length; k++) {
                if (level.getBlockEntity(cp[k]) instanceof ChestBlockEntity ch) {
                    ch.clearContent();
                    int slot = 0;
                    for (ItemStack s : c[k]) {
                        if (!s.isEmpty()) ch.setItem(slot++, s);
                    }
                }
            }
        }
    }

    /** The castle's treasury chests show gold, diamonds and wealth. */
    static void refreshTreasury(ServerLevel level, Tribe t) {
        if (!t.castleDone) return;
        BlockPos o = new BlockPos(t.cx, t.cy, t.cz);
        if (!level.hasChunkAt(o.offset(20, 0, 20))) return;
        BlockPos[] cp = {o.offset(15, 0, 26), o.offset(25, 0, 26)};
        ItemStack[][] c = {
                {stack(Items.GOLD_INGOT, t.gold), stack(Items.GOLD_BLOCK, t.gold / 9), stack(Items.IRON_INGOT, t.iron / 3)},
                {stack(Items.DIAMOND, t.diamond / 3), stack(Items.EMERALD, t.wealth / 100), stack(Items.ENDER_PEARL, t.pearls)}};
        for (int k = 0; k < cp.length; k++) {
            if (level.getBlockEntity(cp[k]) instanceof ChestBlockEntity ch) {
                ch.clearContent();
                int slot = 0;
                for (ItemStack s : c[k]) {
                    if (!s.isEmpty()) ch.setItem(slot++, s);
                }
            }
        }
    }

    // ================= physical war and Nether expeditions =================
    static void manageWar(ServerLevel level, TribeData data, Tribe t, int fighters) {
        long now = level.getGameTime();

        // ---- Nether expedition: warriors walk through the tribe's real portal ----
        if (TribeConfig.PHYSICAL_NETHER && t.stage >= 3 && t.stage <= 5 && t.portalBuilt
                && fighters >= 3 && t.expUntil < now && t.expCool < now) {
            t.expUntil = now + 3000;
            t.expCool = now + 24000;
            TribeLog.add(level, t.name, "sends an expedition into the Nether");
            notifyWithin(level, t.center, 100, "§6[" + t.name + "] A Nether expedition is setting out!");
        }

        if (!TribeConfig.PHYSICAL_RAIDS) return;

        // ---- an army is already marching ----
        if (t.raidTarget != 0) {
            Tribe e = data.tribes.get(t.raidTarget);
            if (e == null || !t.enemies.contains(e.id) || now > t.raidUntil) {
                t.raidTarget = 0;
                return;
            }
            boolean arrived = false;
            for (TribesmanEntity m : members(level, t)) {
                if (m.getRole() == TribesmanEntity.WARRIOR && m.blockPosition().distSqr(e.center) < 15 * 15) { arrived = true; break; }
            }
            if (!arrived) return;
            int defenders = 0;
            for (TribesmanEntity m : members(level, e)) {
                if ((m.getRole() == TribesmanEntity.WARRIOR || m.getRole() == TribesmanEntity.ARCHER)
                        && m.blockPosition().distSqr(e.center) < 45 * 45) defenders++;
            }
            if (defenders > 0) return; // the battle is still going
            int loot = Math.min(e.wealth / 3, 250);
            t.wealth += loot;
            e.wealth -= loot;
            e.food = e.food / 2;
            e.pop = Math.max(1, e.pop - Math.max(2, e.pop / 6));
            t.raidTarget = 0;
            TribeLog.add(level, t.name, "won the battle at " + e.name + " (loot " + loot + ")");
            notifyNear(level, t.center, "§c[WAR] " + t.name + " stormed " + e.name + " and took " + loot + " wealth!");
            int caught = TribeConfig.PRISONERS ? capture(level, t, e) : 0;
            if (caught > 0) {
                notifyNear(level, t.center, "§6[WAR] " + t.name + " marched " + caught + " prisoners of " + e.name + " to the castle jail.");
            }
            if (t.strength() >= e.strength() * 1.4) conquer(level, data, t, e); // the loser becomes part of the empire
            makePeace(level, t, e);
            return;
        }

        // ---- start a new campaign ----
        if (t.enemies.isEmpty() || fighters < 3) return;
        Tribe best = null;
        double bd = 150.0 * 150.0;
        for (int id : t.enemies) {
            Tribe e = data.tribes.get(id);
            if (e == null || !e.founded) continue;
            double d = e.center.distSqr(t.center);
            if (d < bd) { bd = d; best = e; }
        }
        if (best == null) return;
        t.raidTarget = best.id;
        t.raidUntil = now + 4800;
        for (Tribe v : data.tribes.values()) { // vassals send their armies too
            if (v.parent == t.id && v.enemies.contains(best.id)) {
                v.raidTarget = best.id;
                v.raidUntil = now + 4800;
            }
        }
        TribeLog.add(level, t.name, "army marches on " + best.name);
        notifyNear(level, t.center, "§c[WAR] The army of " + t.name + " marches on " + best.name + "!");
    }

    /** A tribesman came back from the Nether through the portal: he brings loot. */
    public static void netherReturn(ServerLevel level, TribesmanEntity m) {
        Tribe t = m.tribe();
        if (t == null) return;
        RandomSource r = level.random;
        int b = 1 + r.nextInt(3), p = 1 + r.nextInt(2);
        t.blaze += b;
        t.pearls += p;
        t.wealth += 20;
        TribeLog.add(level, t.name, m.getName().getString() + " returned from the Nether (+" + b + " blaze, +" + p + " pearls)");
        TribeData.get(level).setDirty();
    }

    // ================= prisoners, jail and trials =================
    /** After a victory: the strongest survivors of the loser are marched into the castle jail. */
    static int capture(ServerLevel level, Tribe winner, Tribe loser) {
        if (!winner.hasJail) return 0;
        BlockPos jail = new BlockPos(winner.jx, winner.jy, winner.jz);
        if (!level.hasChunkAt(jail)) return 0;
        List<TribesmanEntity> pool = new ArrayList<>();
        for (TribesmanEntity m : members(level, loser)) {
            if (!m.isCaptive() && m.blockPosition().distSqr(loser.center) < 70.0 * 70.0) pool.add(m);
        }
        pool.sort((a, b) -> Boolean.compare(b.isFighter(), a.isFighter())); // soldiers first
        int n = 0;
        for (TribesmanEntity m : pool) {
            if (n >= 6) break;
            m.setCaptive(winner.id);
            m.moveTo(jail.getX() + 0.5 + (n % 3) - 1, jail.getY(), jail.getZ() + 0.5 + (n / 3) - 0.5, 0f, 0f);
            n++;
        }
        if (n > 0) TribeLog.add(level, winner.name, "took " + n + " prisoners from " + loser.name);
        return n;
    }

    /** Prisoners wait two minutes, then the tribe holds a trial for each of them. */
    static void manageJail(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        if (!t.hasJail) return;
        BlockPos j = new BlockPos(t.jx, t.jy, t.jz);
        if (!level.hasChunkAt(j)) return;
        for (TribesmanEntity p : level.getEntitiesOfClass(TribesmanEntity.class, new AABB(j).inflate(6.0))) {
            if (p.isAlive() && p.captor() == t.id && p.captiveTicks() >= 2400) trial(level, data, t, p, rnd);
        }
    }

    static void trial(ServerLevel level, TribeData data, Tribe judge, TribesmanEntity p, RandomSource rnd) {
        Tribe home = data.tribes.get(p.getTribeId());
        String who = p.baseName().isEmpty() ? "a prisoner" : p.baseName();
        BlockPos j = new BlockPos(judge.jx, judge.jy, judge.jz);
        double guilt = p.isFighter() ? 0.7 : 0.25;
        if (home != null && judge.enemies.contains(home.id)) guilt += 0.1;
        String verdict;
        if (rnd.nextDouble() >= guilt) {
            p.setCaptive(0);
            p.discard(); // free: he walks home
            verdict = "acquitted and sent home";
        } else if (judge.trait == 0 && rnd.nextDouble() < 0.4) {
            p.setCaptive(0);
            p.hurt(level.damageSources().generic(), 1000.0f);
            if (home != null) home.grievance.merge(judge.id, 4, Integer::sum); // his people will not forget
            verdict = "found guilty and EXECUTED";
        } else if (rnd.nextDouble() < 0.55) {
            int role = p.getRole();
            p.setCaptive(0);
            p.setupTribe(judge, role);
            if (level.hasChunkAt(judge.center)) {
                p.moveTo(judge.center.getX() + 0.5, TribeManager.groundY(level, judge.center.getX(), judge.center.getZ()),
                        judge.center.getZ() + 0.5, 0f, 0f);
            }
            judge.pop++;
            if (home != null) home.pop = Math.max(1, home.pop - 1);
            verdict = "found guilty - sentenced to forced labour for " + judge.name;
        } else {
            int ransom = home == null ? 0 : Math.min(home.wealth / 4, 120);
            judge.wealth += ransom;
            if (home != null) home.wealth -= ransom;
            p.setCaptive(0);
            p.discard();
            verdict = "found guilty - released for a ransom of " + ransom;
        }
        TribeLog.add(level, judge.name, "TRIAL: " + who + " " + verdict);
        notifyWithin(level, j, 120, "§6[TRIAL] " + judge.name + " tried " + who + ": " + verdict + ".");
        TribeData.get(level).setDirty();
    }
}
