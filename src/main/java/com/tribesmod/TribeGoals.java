package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

/** All the "player-like" behaviours of a tribesman. */
public class TribeGoals {

    // ================= helpers =================
    static boolean night(Level l) {
        long t = l.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    /** Workers rest at night; warriors keep guard. */
    static boolean resting(TribesmanEntity m) {
        if (m.isFighter()) return false;
        if (TribeConfig.NIGHT_REST && night(m.level())) return true;
        if (!TribeConfig.INSTINCTS) return false;
        if (m.getHealth() < m.getMaxHealth() * 0.6f) return true; // hurt: recover at the campfire first
        Tribe t = m.tribe();
        return t != null && t.alarmUntil > m.level().getGameTime(); // alarm: take cover with the warriors
    }

    static void walk(TribesmanEntity m, BlockPos p, double speed) {
        m.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
    }

    /** Stuck for too long: hop to the spot, but only when no player is watching. */
    static boolean unstick(TribesmanEntity m, BlockPos p) {
        if (m.level().getNearestPlayer(m, 24.0) != null) return false;
        m.getNavigation().stop();
        m.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, m.getYRot(), 0f);
        return true;
    }

    /** Pick up every loose item around a point (like a player grabbing the drops). */
    static void collectNear(TribesmanEntity m, ServerLevel level, BlockPos c, double r) {
        Tribe t = m.tribe();
        if (t == null) return;
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(c).inflate(r))) {
            if (!ie.isAlive() || ie.getItem().isEmpty()) continue;
            ItemStack s = ie.getItem();
            m.pick(s);
            m.take(ie, s.getCount());
            ie.discard();
        }
        TribeData.get(level).setDirty();
    }

    static Block saplingFor(BlockState log) {
        if (log.is(Blocks.SPRUCE_LOG)) return Blocks.SPRUCE_SAPLING;
        if (log.is(Blocks.BIRCH_LOG)) return Blocks.BIRCH_SAPLING;
        if (log.is(Blocks.JUNGLE_LOG)) return Blocks.JUNGLE_SAPLING;
        if (log.is(Blocks.ACACIA_LOG)) return Blocks.ACACIA_SAPLING;
        if (log.is(Blocks.DARK_OAK_LOG)) return Blocks.DARK_OAK_SAPLING;
        if (log.is(Blocks.CHERRY_LOG)) return Blocks.CHERRY_SAPLING;
        return Blocks.OAK_SAPLING;
    }

    /** Base for goals that walk somewhere. Cooldowns use the entity's own tick counter. */
    abstract static class Base extends Goal {
        final TribesmanEntity m;
        int next;

        Base(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        boolean ready() { return m.tickCount >= next; }
        void pause(int ticks) { next = m.tickCount + ticks; }
        boolean server() { return m.level() instanceof ServerLevel; }
        ServerLevel sl() { return (ServerLevel) m.level(); }
    }

    // ================= farmer + woodcutter =================
    /**
     * Farmer: harvests ripe crops (real drops), replants, plants empty farmland.
     * Woodcutter: chops a tree with crack animation, fells the whole trunk, replants the right sapling.
     */
    public static class WorkGoal extends Base {
        private BlockPos target, bad, treeBase;
        private BlockState treeState;
        private int mode; // 0 harvest, 1 plant, 2 chop, 3 felling
        private int timeout, stuck, chop;
        private final ArrayDeque<BlockPos> felling = new ArrayDeque<>();

        public WorkGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            int tk = m.task();
            if ((tk != TribeBrain.FARM && tk != TribeBrain.WOOD) || !server() || !ready() || resting(m) || m.getTarget() != null) return false;
            if (!m.hasTool()) return false; // StoreGoal fetches the tool first
            if (!felling.isEmpty() && treeBase != null) {
                target = treeBase;
                mode = 3;
                return true;
            }
            target = find(sl());
            if (target == null) { m.fail(tk); pause(50 + m.getRandom().nextInt(40)); return false; }
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && timeout > 0 && !resting(m) && m.getTarget() == null && m.task() == (mode <= 1 ? TribeBrain.FARM : TribeBrain.WOOD);
        }

        @Override
        public void start() {
            timeout = 500;
            stuck = 0;
            chop = 0;
            m.setStatus(mode == 0 ? "Harvesting" : mode == 1 ? "Planting" : "Chopping tree");
            walk(m, target, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (target == null) return;
            double d = m.distanceToSqr(Vec3.atCenterOf(target));
            double reach = mode <= 1 ? 6.0 : 14.0;
            if (d <= reach) {
                m.getNavigation().stop();
                m.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                act(sl());
            } else if (m.getNavigation().isDone()) {
                if (++stuck > 3) {
                    bad = target; // unreachable, try something else
                    TribeLog.add(sl(), m.getName().getString(), "cannot reach work spot at " + target.getX() + " " + target.getY() + " " + target.getZ());
                    target = null;
                    pause(40);
                } else {
                    walk(m, target, 1.0);
                }
            }
        }

        @Override
        public void stop() {
            if (m.level() instanceof ServerLevel level && target != null && mode == 2) {
                level.destroyBlockProgress(m.getId(), target, -1);
            }
            target = null;
            m.getNavigation().stop();
            m.setStatus("");
            m.refreshGear();
        }

        private void finish(int cooldown) {
            target = null;
            pause(cooldown);
        }

        private void act(ServerLevel level) {
            Tribe t = m.tribe();
            if (t == null) { target = null; return; }
            BlockState s = level.getBlockState(target);
            switch (mode) {
                case 0 -> {
                    if (s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s)) {
                        level.destroyBlock(target, true);
                        level.setBlock(target, cb.getStateForAge(0), 3);
                        m.swing(InteractionHand.MAIN_HAND);
                        collectNear(m, level, target, 3.5);
                        m.addXp(1);
                    }
                    finish(6);
                }
                case 1 -> {
                    if (s.isAir() && level.getBlockState(target.below()).is(Blocks.FARMLAND)) {
                        Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES};
                        level.setBlock(target, crops[Math.floorMod(target.getX() * 31 + target.getZ() * 17, 3)].defaultBlockState(), 3);
                        m.swing(InteractionHand.MAIN_HAND);
                        m.addXp(1);
                    }
                    finish(4);
                }
                case 2 -> {
                    if (!s.is(BlockTags.LOGS)) { finish(10); return; }
                    int need = Math.max(6, 26 - 2 * m.skill() - 4 * t.tier());
                    chop++;
                    if (chop % 4 == 0) m.swing(InteractionHand.MAIN_HAND);
                    level.destroyBlockProgress(m.getId(), target, Math.min(9, chop * 10 / need));
                    if (chop >= need) {
                        level.destroyBlockProgress(m.getId(), target, -1);
                        treeBase = target;
                        treeState = s;
                        felling.clear();
                        felling.addAll(treeLogs(level, target));
                        mode = 3;
                        chop = 0;
                    }
                }
                case 3 -> {
                    if (m.tickCount % 4 != 0) return;
                    BlockPos p = felling.poll(); // highest log first
                    if (p != null && level.getBlockState(p).is(BlockTags.LOGS)) {
                        level.destroyBlock(p, true);
                        m.swing(InteractionHand.MAIN_HAND);
                    }
                    if (felling.isEmpty()) {
                        BlockPos base = treeBase;
                        if (base != null && treeState != null && level.getBlockState(base).isAir()
                                && level.getBlockState(base.below()).is(BlockTags.DIRT)) {
                            level.setBlock(base, saplingFor(treeState).defaultBlockState(), 3);
                        }
                        if (base != null) collectNear(m, level, base, 8.0);
                        m.addXp(3);
                        treeBase = null;
                        finish(20);
                    }
                }
                default -> finish(10);
            }
        }

        private List<BlockPos> treeLogs(ServerLevel level, BlockPos base) {
            ArrayList<BlockPos> out = new ArrayList<>();
            HashSet<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            q.add(base);
            seen.add(base);
            while (!q.isEmpty() && out.size() < 64) {
                BlockPos p = q.poll();
                out.add(p);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos n = p.offset(dx, dy, dz);
                            if (seen.contains(n)) continue;
                            if (Math.abs(n.getX() - base.getX()) > 6 || Math.abs(n.getZ() - base.getZ()) > 6
                                    || n.getY() - base.getY() > 40) continue;
                            if (level.getBlockState(n).is(BlockTags.LOGS)) {
                                seen.add(n);
                                q.add(n);
                            }
                        }
                    }
                }
            }
            out.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
            return out;
        }

        private BlockPos find(ServerLevel level) {
            Tribe t = m.tribe();
            if (t == null) return null;
            BlockPos c = m.blockPosition();
            double lim = t.territory() + 4;
            double best = Double.MAX_VALUE;
            BlockPos bp = null;
            int bestMode = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -2, -12), c.offset(12, 3, 12))) {
                if (p.distSqr(t.center) > lim * lim || p.equals(bad) || t.unsafe(p)) continue;
                BlockState s = level.getBlockState(p);
                int md = -1;
                if (m.task() == TribeBrain.FARM) {
                    if (s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s)) md = 0;
                    else if (s.isAir() && level.getBlockState(p.below()).is(Blocks.FARMLAND)) md = 1;
                } else if (s.is(BlockTags.LOGS) && level.getBlockState(p.below()).is(BlockTags.DIRT) && isTree(level, p)) {
                    md = 2;
                }
                if (md < 0) continue;
                double d = p.distSqr(c) + (md == 1 ? 8 : 0); // harvesting beats planting
                if (d < best) { best = d; bp = p.immutable(); bestMode = md; }
            }
            mode = bestMode;
            return bp;
        }

        private boolean isTree(ServerLevel level, BlockPos p) {
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 1, -2), p.offset(2, 8, 2))) {
                if (level.getBlockState(q).is(BlockTags.LEAVES)) return true;
            }
            return false;
        }
    }

    // ================= everyone: pick up loose items =================
    public static class CollectItemsGoal extends Base {
        private ItemEntity target;
        private int timeout;

        public CollectItemsGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!TribeConfig.PICK_UP_ITEMS || m.getRole() == TribesmanEntity.ARCHER || !server() || !ready() || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null) { pause(100); return false; }
            ServerLevel level = sl();
            double lim = t.territory() + 24;
            ItemEntity best = null;
            double bd = Double.MAX_VALUE;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, m.getBoundingBox().inflate(12.0, 4.0, 12.0))) {
                if (!e.isAlive() || e.getItem().isEmpty() || e.tickCount < 25) continue;
                if (e.blockPosition().distSqr(t.center) > lim * lim) continue;
                if (level.getNearestPlayer(e, 3.0) != null) continue; // let the player grab their own drops
                if (!m.isFighter() && t.unsafe(e.blockPosition())) continue; // learned: that place is dangerous
                double d = m.distanceToSqr(e);
                if (d < bd) { bd = d; best = e; }
            }
            if (best == null) { pause(30 + m.getRandom().nextInt(20)); return false; }
            target = best;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive() && timeout > 0 && m.getTarget() == null;
        }

        @Override
        public void start() {
            timeout = 200;
            m.setStatus("Picking up items");
            m.getNavigation().moveTo(target, 1.1);
        }

        @Override
        public void tick() {
            timeout--;
            if (target == null) return;
            if (m.distanceToSqr(target) < 2.6) {
                Tribe t = m.tribe();
                ItemStack s = target.getItem();
                if (t != null && !s.isEmpty()) {
                    m.pick(s);
                    m.take(target, s.getCount());
                    target.discard();
                    m.addXp(1);
                    TribeData.get(sl()).setDirty();
                }
                target = null;
                pause(4);
            } else if (m.getNavigation().isDone()) {
                m.getNavigation().moveTo(target, 1.1);
            }
        }

        @Override
        public void stop() {
            target = null;
            m.setStatus("");
        }
    }

    // ================= builder =================
    /** Builder: walks to the construction site and places the blueprint block by block. */
    public static class BuildGoal extends Base {
        private int timeout, stuck, cacheKey = Integer.MIN_VALUE;
        private List<Blueprints.Placement> cache = List.of();

        public BuildGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (m.task() != TribeBrain.BUILD || !server() || !ready() || resting(m) || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null || t.bType < 0) { m.fail(TribeBrain.BUILD); pause(60); return false; }
            return m.hasTool();
        }

        @Override
        public boolean canContinueToUse() {
            Tribe t = m.tribe();
            return timeout > 0 && t != null && t.bType >= 0 && !resting(m) && m.getTarget() == null && m.task() == TribeBrain.BUILD;
        }

        @Override
        public void start() {
            timeout = 1200;
            stuck = 0;
            Tribe t = m.tribe();
            m.setStatus(t == null ? "Building" : "Building " + Blueprints.name(t.bType));
        }

        @Override
        public void stop() {
            m.getNavigation().stop();
            m.setStatus("");
            m.refreshGear();
        }

        private List<Blueprints.Placement> plan(Tribe t) {
            int key = ((t.bType * 31 + t.bx) * 31 + t.by) * 31 + t.bz;
            if (key != cacheKey) {
                cacheKey = key;
                cache = Blueprints.make(t.bType, new BlockPos(t.bx, t.by, t.bz), false);
            }
            return cache;
        }

        @Override
        public void tick() {
            timeout--;
            Tribe t = m.tribe();
            if (t == null || t.bType < 0) return;
            ServerLevel level = sl();
            List<Blueprints.Placement> list = plan(t);
            if (t.bProg >= list.size()) {
                TribeManager.finishJob(level, t);
                return;
            }
            Blueprints.Placement p = list.get(t.bProg);
            BlockPos real = Blueprints.resolve(level, p);
            if (real == null) { // chunk not loaded: walk toward it and wait
                walk(m, new BlockPos(p.pos().getX(), t.by, p.pos().getZ()), 1.0);
                return;
            }
            boolean big = Blueprints.large(t.bType);
            BlockPos stand = p.surface() ? real
                    : big ? new BlockPos(real.getX(), t.by, real.getZ())
                    : new BlockPos(t.bx + 2, t.by, t.bz - 2);
            double dx = m.getX() - (real.getX() + 0.5);
            double dz = m.getZ() - (real.getZ() + 0.5);
            double dy = m.getY() - real.getY();
            boolean inReach = dx * dx + dz * dz <= 36.0 && Math.abs(dy) <= (big ? 17.0 : 10.0);
            if (!inReach) {
                if (m.getNavigation().isDone()) {
                    if (++stuck > 3) {
                        stuck = 0;
                        if (!unstick(m, stand)) timeout = 0;
                    } else {
                        walk(m, stand, 1.0);
                    }
                }
                return;
            }
            m.getNavigation().stop();
            m.getLookControl().setLookAt(real.getX() + 0.5, real.getY() + 0.5, real.getZ() + 0.5);
            if (m.tickCount % 5 != 0) return;
            placeOne(level, t, p, real, stand);
        }

        private void placeOne(ServerLevel level, Tribe t, Blueprints.Placement p, BlockPos pos, BlockPos stand) {
            BlockState cur = level.getBlockState(pos);
            if (cur.equals(p.state())) { t.bProg++; return; }
            if (!Blueprints.canOverwrite(level, p, pos)) { t.bProg++; return; } // something else is standing there
            if (cur.hasBlockEntity()) { t.bProg++; return; } // never overwrite chests and such
            int kind = Blueprints.costKind(p.state());
            if (!Blueprints.pay(t, kind, t.bProg)) {
                pause(100); // waiting for wood / stone from the other workers
                timeout = 0;
                return;
            }
            if (p.state().blocksMotion() && m.getBoundingBox().intersects(new AABB(pos))) {
                walk(m, stand, 1.1); // do not wall yourself in
                return;
            }
            if (!cur.isAir() && !cur.canBeReplaced()) level.destroyBlock(pos, false);
            level.setBlock(pos, p.state(), 3);
            level.playSound(null, pos, p.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
            m.hold(p.state());
            m.swing(InteractionHand.MAIN_HAND);
            m.addXp(1);
            t.bProg++;
            t.bIdle = 0;
            TribeData.get(level).setDirty();
        }
    }

    // ================= trader =================
    /** Trader: walks to a friendly nearby tribe and trades. */
    public static class TradeGoal extends Base {
        private Tribe target;
        private int timeout;

        public TradeGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (m.task() != TribeBrain.TRADE || !server() || !ready() || resting(m) || m.getTarget() != null) return false;
            ServerLevel sl = sl();
            Tribe mine = m.tribe();
            if (mine == null) { pause(100); return false; }
            double best = 120 * 120;
            Tribe bt = null;
            for (Tribe o : TribeData.get(sl).tribes.values()) {
                if (o.id == mine.id || mine.enemies.contains(o.id)) continue;
                double d = o.center.distSqr(m.blockPosition());
                if (d < best && o.center.distSqr(mine.center) > 24 * 24) { best = d; bt = o; }
            }
            if (bt == null) { m.fail(TribeBrain.TRADE); pause(200); return false; }
            target = bt;
            return true;
        }

        @Override
        public boolean canContinueToUse() { return target != null && timeout > 0 && !resting(m); }

        @Override
        public void start() {
            timeout = 1200;
            m.setStatus("Trading trip to " + target.name);
            walk(m, target.center, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (target == null) return;
            if (m.blockPosition().distSqr(target.center) < 36) {
                Tribe mine = m.tribe();
                if (mine != null) {
                    mine.wealth += 6;
                    target.wealth += 3;
                    if (mine.food > target.food + 20) { mine.food -= 10; target.food += 10; }
                    else if (target.food > mine.food + 20) { target.food -= 10; mine.food += 10; }
                    m.swing(InteractionHand.MAIN_HAND);
                    m.addXp(2);
                    TribeData.get(sl()).setDirty();
                }
                target = null;
                pause(1200);
            } else if (m.getNavigation().isDone()) {
                walk(m, target.center, 1.0);
            }
        }

        @Override
        public void stop() {
            target = null;
            m.setStatus("");
            m.getNavigation().stop();
        }
    }

    // ================= warrior =================
    /** Warrior: when the tribe's army is ordered to march, everyone walks to the enemy village and fights. */
    public static class RaidGoal extends Base {
        private Tribe target;
        private int timeout;

        public RaidGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!m.raiding() || !TribeConfig.PHYSICAL_RAIDS || !server() || !ready()) return false;
            Tribe mine = m.tribe();
            if (mine == null || mine.raidTarget == 0 || sl().getGameTime() > mine.raidUntil) { pause(100); return false; }
            Tribe e = TribeData.get(sl()).tribes.get(mine.raidTarget);
            if (e == null) { pause(100); return false; }
            if (m.blockPosition().distSqr(e.center) < 10 * 10) { pause(40); return false; } // already there - fight
            target = e;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe mine = m.tribe();
            return target != null && timeout > 0 && mine != null && mine.raidTarget == target.id
                    && m.blockPosition().distSqr(target.center) > 10 * 10;
        }

        @Override
        public void start() {
            timeout = 1200;
            m.setStatus("Marching on " + target.name);
            walk(m, target.center, 1.1);
        }

        @Override
        public void tick() {
            timeout--;
            if (target != null && m.getNavigation().isDone()) walk(m, target.center, 1.1);
        }

        @Override
        public void stop() {
            target = null;
            m.setStatus("");
            pause(60);
            m.getNavigation().stop();
        }
    }

    /** Warrior: patrols around the village when there is nothing to fight. */
    public static class PatrolGoal extends Base {
        private BlockPos dest;
        private int timeout;

        public PatrolGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            boolean patrols = m.isFighter() && m.task() == TribeBrain.GUARD
                    && !(m.getRole() == TribesmanEntity.ARCHER && m.post() != null);
            if (!patrols || !server() || !ready() || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null) { pause(100); return false; }
            int r = Math.max(8, t.territory() - 8);
            int x = t.center.getX() + m.getRandom().nextInt(2 * r + 1) - r;
            int z = t.center.getZ() + m.getRandom().nextInt(2 * r + 1) - r;
            BlockPos probe = new BlockPos(x, 64, z);
            if (!sl().hasChunkAt(probe)) { pause(100); return false; }
            dest = new BlockPos(x, TribeManager.groundY(sl(), x, z), z);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return dest != null && timeout > 0 && m.getTarget() == null && !m.getNavigation().isDone();
        }

        @Override
        public void start() {
            timeout = 400;
            m.setStatus("Patrolling");
            walk(m, dest, 0.9);
        }

        @Override
        public void tick() { timeout--; }

        @Override
        public void stop() {
            dest = null;
            m.setStatus("");
            pause(120 + m.getRandom().nextInt(200));
        }
    }

    /** When one tribesman is in a fight, the warriors of his tribe come to help. */
    public static class HelpGoal extends Goal {
        private final TribesmanEntity m;
        private int next;

        public HelpGoal(TribesmanEntity m) { this.m = m; }

        @Override
        public boolean canUse() {
            return m.getTarget() != null && m.tickCount >= next && m.level() instanceof ServerLevel;
        }

        @Override
        public boolean canContinueToUse() { return false; }

        @Override
        public void start() {
            next = m.tickCount + 30;
            LivingEntity target = m.getTarget();
            if (target == null) return;
            if (target instanceof TribesmanEntity tt && tt.getTribeId() == m.getTribeId()) return;
            int n = 0;
            for (TribesmanEntity o : m.level().getEntitiesOfClass(TribesmanEntity.class, m.getBoundingBox().inflate(20.0),
                    e -> e != m && e.isAlive() && e.getTribeId() == m.getTribeId()
                            && e.getTarget() == null && e.isFighter())) {
                o.setTarget(target);
                if (++n >= 5) break;
            }
        }
    }

    /** Eat food from the tribe stock when hurt, like a player. Runs alongside other goals. */
    public static class EatGoal extends Goal {
        private final TribesmanEntity m;
        private int eating, next;

        public EatGoal(TribesmanEntity m) { this.m = m; }

        @Override
        public boolean canUse() {
            if (!(m.level() instanceof ServerLevel) || m.tickCount < next) return false;
            float frac = m.getHealth() / m.getMaxHealth();
            LivingEntity tg = m.getTarget();
            if (tg != null && tg.isAlive() && m.distanceToSqr(tg) < 49.0) return false; // never eat with an enemy within 7 blocks
            if (frac >= 0.8f) return false;
            Tribe t = m.tribe();
            if (t == null) return false;
            if (t.hasStore && TribeConfig.STORAGE_TRIPS) return m.rations() > 0; // food comes from the storehouse
            return t.food >= 6;
        }

        @Override
        public boolean canContinueToUse() { return eating > 0 && m.isAlive(); }

        @Override
        public void start() {
            eating = 32;
            m.setStatus("Eating");
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BREAD));
        }

        @Override
        public void tick() {
            eating--;
            if (eating % 6 == 0 && m.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, m.getX(), m.getEyeY(), m.getZ(), 3, 0.2, 0.1, 0.2, 0.0);
            }
            if (eating == 0) {
                Tribe t = m.tribe();
                if (t != null) {
                    if (t.hasStore && TribeConfig.STORAGE_TRIPS) {
                        if (m.rations() > 0) { m.useRation(); m.heal(7.0f); }
                    } else if (t.food >= 6) {
                        t.food -= 6;
                        m.heal(7.0f);
                    }
                }
            }
        }

        @Override
        public void stop() {
            next = m.tickCount + 160;
            m.setStatus("");
            m.refreshGear();
        }
    }

    // ================= miner =================
    /** Miner: walks to the dig face of the tribe's mine and really digs it out (stairs, tunnels, ore veins). */
    public static class MineGoal extends Base {
        private int timeout, stuck, swing;

        public MineGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (m.task() != TribeBrain.MINE || !server() || !ready() || resting(m)) return false;
            Tribe t = m.tribe();
            if (t == null || m.getTarget() != null) { pause(40); return false; }
            BlockPos f = TribeMine.face(sl(), t);
            if (f == null) { m.fail(TribeBrain.MINE); pause(100); return false; }
            return m.hasTool();
        }

        @Override
        public boolean canContinueToUse() {
            return timeout > 0 && m.getTarget() == null && m.tribe() != null && !resting(m) && m.task() == TribeBrain.MINE;
        }

        @Override
        public void start() {
            timeout = 600;
            stuck = 0;
            swing = 0;
            m.setStatus("Mining");
        }

        @Override
        public void tick() {
            timeout--;
            Tribe t = m.tribe();
            if (t == null) return;
            ServerLevel sl = sl();
            BlockPos f = TribeMine.head(t);
            double d = m.distanceToSqr(Vec3.atCenterOf(f));
            if (d < 20.0) {
                m.getNavigation().stop();
                m.getLookControl().setLookAt(f.getX() + 0.5, f.getY() + 1.0, f.getZ() + 0.5);
                if (++swing >= 30) {
                    swing = 0;
                    if (TribeMine.dig(sl, t, 1) > 0) {
                        m.swing(InteractionHand.MAIN_HAND);
                        m.addXp(1);
                        TribeData.get(sl).setDirty();
                    } else if (!TribeMine.ensure(sl, t)) {
                        timeout = 0;
                    }
                }
            } else if (m.getNavigation().isDone()) {
                if (++stuck > 2 || d > 30 * 30) {
                    stuck = 0;
                    m.moveTo(f.getX() + 0.5, f.getY(), f.getZ() + 0.5, m.getYRot(), 0f);
                    m.getNavigation().stop();
                } else {
                    walk(m, f, 1.0);
                }
            }
        }

        @Override
        public void stop() {
            m.getNavigation().stop();
            m.setStatus("");
            pause(20);
        }
    }

    // ================= night + home =================
    /** At night workers gather around the campfire and rest. */
    public static class NightGoal extends Base {
        private BlockPos spot;
        private int timeout;

        public NightGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!resting(m) || !server() || !ready() || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null) return false;
            if (m.blockPosition().distSqr(t.center) < 7 * 7) { pause(100); return false; }
            int x = t.center.getX() + m.getRandom().nextInt(7) - 3;
            int z = t.center.getZ() + m.getRandom().nextInt(7) - 3;
            spot = new BlockPos(x, TribeManager.groundY(sl(), x, z), z);
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return spot != null && timeout > 0 && resting(m) && m.getTarget() == null && !m.getNavigation().isDone();
        }

        @Override
        public void start() {
            timeout = 600;
            Tribe t = m.tribe();
            boolean alarm = t != null && t.alarmUntil > sl().getGameTime();
            m.setStatus(alarm ? "ALARM - taking cover" : night(m.level()) ? "Resting at the campfire" : "Recovering");
            walk(m, spot, alarm ? 1.25 : 1.0);
        }

        @Override
        public void tick() { timeout--; }

        @Override
        public void stop() {
            spot = null;
            m.setStatus("");
            pause(60);
        }
    }

    /** Everyone drifts back home if they wander too far. */
    public static class ReturnHomeGoal extends Base {
        public ReturnHomeGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            Tribe t = m.tribe();
            if (t == null || m.getTarget() != null) return false;
            if (m.getRole() == TribesmanEntity.MINER && TribeMine.ready(t)) return false;
            double lim = t.territory();
            return m.blockPosition().distSqr(t.center) > lim * lim;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe t = m.tribe();
            return t != null && !m.getNavigation().isDone() && m.blockPosition().distSqr(t.center) > 64;
        }

        @Override
        public void start() {
            Tribe t = m.tribe();
            if (t != null) walk(m, t.center, 1.0);
        }
    }

    // ================= archers =================
    /** Archer: keeps its distance and shoots arrows (real arrow entities). */
    public static class ArcherGoal extends Goal {
        private final TribesmanEntity m;
        private int cool;

        public ArcherGoal(TribesmanEntity m) {
            this.m = m;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = m.getTarget();
            return TribeConfig.ARCHERS && m.getRole() == TribesmanEntity.ARCHER && t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() { return canUse(); }

        @Override
        public void start() { m.setStatus("Shooting"); }

        @Override
        public void stop() {
            m.setStatus("");
            m.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity tg = m.getTarget();
            if (tg == null) return;
            double d = m.distanceToSqr(tg);
            boolean los = m.hasLineOfSight(tg);
            m.getLookControl().setLookAt(tg, 30.0f, 30.0f);
            boolean onPost = m.post() != null;
            if (onPost && d > 28.0 * 28.0) { m.setTarget(null); return; } // too far for a guard to bother
            if (d > 20.0 * 20.0 || !los) {
                if (!onPost) m.getNavigation().moveTo(tg, 1.0);
            } else {
                m.getNavigation().stop();
            }
            if (cool > 0) cool--;
            if (cool == 0 && los && d < 24.0 * 24.0) {
                shoot(tg);
                cool = Math.max(14, 28 - m.skill());
            }
        }

        private void shoot(LivingEntity tg) {
            Tribe t = m.tribe();
            Arrow a = new Arrow(EntityType.ARROW, m.level());
            a.setPos(m.getX(), m.getEyeY() - 0.1, m.getZ());
            a.setOwner(m);
            a.pickup = AbstractArrow.Pickup.DISALLOWED;
            a.setBaseDamage(2.0 + (t == null ? 0 : 0.4 * t.stage));
            double dx = tg.getX() - m.getX();
            double dy = tg.getY(0.33) - a.getY();
            double dz = tg.getZ() - m.getZ();
            double h = Math.sqrt(dx * dx + dz * dz);
            a.shoot(dx, dy + h * 0.2, dz, 1.6f, 2.0f);
            m.level().addFreshEntity(a);
            m.swing(InteractionHand.MAIN_HAND);
            m.addXp(1);
        }
    }

    /** Archer: stays on its watchtower. */
    public static class PostGoal extends Base {
        public PostGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            return TribeConfig.ARCHERS && m.getRole() == TribesmanEntity.ARCHER && m.post() != null
                    && m.getTarget() == null && server();
        }

        @Override
        public boolean canContinueToUse() { return canUse(); }

        @Override
        public void start() { m.setStatus("Guarding the tower"); }

        @Override
        public void stop() { m.setStatus(""); }

        @Override
        public void tick() {
            BlockPos p = m.post();
            if (p == null) return;
            if (m.blockPosition().distSqr(p) > 9.0 && m.tickCount % 40 == 0) {
                if (m.level().getNearestPlayer(m, 24.0) == null) {
                    m.getNavigation().stop();
                    m.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, m.getYRot(), 0f);
                } else {
                    walk(m, p, 1.0);
                }
            }
        }
    }

    // ================= farmer: animals + fishing =================
    /** Farmer: breeds animals in the pens and slaughters the surplus for meat and leather. */
    public static class AnimalGoal extends Base {
        private Animal a, b;
        private boolean cull;
        private int timeout;

        public AnimalGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!TribeConfig.ANIMALS || m.task() != TribeBrain.ANIMALS || !server() || !ready()
                    || resting(m) || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null) { pause(100); return false; }
            if (!m.hasTool()) return false;
            ServerLevel level = sl();
            for (BlockPos o : TribeManager.pens(level, t)) {
                List<Animal> list = level.getEntitiesOfClass(Animal.class, new AABB(o.offset(2, 0, 2)).inflate(6.0));
                if (list.isEmpty()) continue;
                if (list.size() > 7) {
                    for (Animal x : list) {
                        if (x.getAge() >= 0 && x.isAlive()) { a = x; cull = true; return true; }
                    }
                } else if (list.size() < 7 && t.food >= 5) {
                    for (Animal x : list) {
                        if (x.getAge() != 0 || x.isInLove() || !x.isAlive()) continue;
                        for (Animal y : list) {
                            if (y != x && y.getType() == x.getType() && y.getAge() == 0 && !y.isInLove() && y.isAlive()) {
                                a = x;
                                b = y;
                                cull = false;
                                return true;
                            }
                        }
                    }
                }
            }
            m.fail(TribeBrain.ANIMALS);
            pause(120 + m.getRandom().nextInt(80));
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return a != null && a.isAlive() && timeout > 0 && !resting(m) && m.getTarget() == null;
        }

        @Override
        public void start() {
            timeout = 400;
            m.setStatus(cull ? "Slaughtering livestock" : "Breeding animals");
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(cull ? Items.IRON_SWORD : Items.WHEAT));
            m.getNavigation().moveTo(a, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (a == null) return;
            m.getLookControl().setLookAt(a, 30.0f, 30.0f);
            if (m.distanceToSqr(a) < 6.0) {
                ServerLevel level = sl();
                Tribe t = m.tribe();
                m.swing(InteractionHand.MAIN_HAND);
                if (cull) {
                    a.hurt(level.damageSources().generic(), 100.0f);
                    collectNear(m, level, a.blockPosition(), 3.5);
                } else if (t != null) {
                    a.setInLove(null);
                    if (b != null) b.setInLove(null);
                    t.food -= 2;
                }
                m.addXp(2);
                a = null;
                pause(80);
            } else if (m.getNavigation().isDone()) {
                m.getNavigation().moveTo(a, 1.0);
            }
        }

        @Override
        public void stop() {
            a = null;
            b = null;
            m.setStatus("");
            m.refreshGear();
        }
    }

    /** Farmer: goes fishing at a nearby lake when there is nothing else to do. */
    public static class FishGoal extends Base {
        private BlockPos spot, water;
        private int timeout, fishing;

        public FishGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!TribeConfig.ANIMALS || m.task() != TribeBrain.FISH || !server() || !ready()
                    || resting(m) || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null) { pause(200); return false; }
            if (!m.hasTool()) return false;
            ServerLevel level = sl();
            for (int tries = 0; tries < 12; tries++) {
                int x = t.center.getX() + m.getRandom().nextInt(81) - 40;
                int z = t.center.getZ() + m.getRandom().nextInt(81) - 40;
                if (!level.hasChunkAt(new BlockPos(x, 64, z))) continue;
                int y = TribeManager.groundY(level, x, z);
                BlockPos w = new BlockPos(x, y - 1, z);
                if (!level.getFluidState(w).is(FluidTags.WATER) || t.unsafe(w)) continue;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        int sx = x + dx, sz = z + dz;
                        if (!level.hasChunkAt(new BlockPos(sx, 64, sz))) continue;
                        int sy = TribeManager.groundY(level, sx, sz);
                        BlockPos below = new BlockPos(sx, sy - 1, sz);
                        if (Math.abs(sy - y) <= 1 && level.getFluidState(below).isEmpty() && level.getBlockState(below).is(BlockTags.DIRT)) {
                            water = w;
                            spot = new BlockPos(sx, sy, sz);
                            return true;
                        }
                    }
                }
            }
            m.fail(TribeBrain.FISH);
            pause(200);
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return spot != null && timeout > 0 && !resting(m) && m.getTarget() == null;
        }

        @Override
        public void start() {
            timeout = 700;
            fishing = 0;
            m.setStatus("Going fishing");
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
            walk(m, spot, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            if (spot == null) return;
            if (m.blockPosition().distSqr(spot) < 6.0) {
                m.getNavigation().stop();
                m.setStatus("Fishing");
                m.getLookControl().setLookAt(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
                fishing++;
                if (fishing % 40 == 0) m.swing(InteractionHand.MAIN_HAND);
                if (fishing >= 200) {
                    ServerLevel level = sl();
                    m.spawnAtLocation(new ItemStack(m.getRandom().nextBoolean() ? Items.COD : Items.SALMON));
                    collectNear(m, level, m.blockPosition(), 2.5);
                    m.addXp(2);
                    spot = null;
                    pause(300);
                }
            } else if (m.getNavigation().isDone()) {
                walk(m, spot, 1.0);
            }
        }

        @Override
        public void stop() {
            spot = null;
            m.setStatus("");
            m.refreshGear();
        }
    }

    // ================= the Nether =================
    /** Warrior: walks through the tribe's real Nether portal when the tribe sends an expedition. */
    public static class ExpeditionGoal extends Base {
        private int timeout, phase;

        public ExpeditionGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (m.getRole() != TribesmanEntity.WARRIOR || !TribeConfig.PHYSICAL_NETHER || !server() || !ready()) return false;
            if (m.level().dimension() != Level.OVERWORLD) return false;
            Tribe t = m.tribe();
            if (t == null || !t.portalBuilt || sl().getGameTime() > t.expUntil) { pause(100); return false; }
            if (m.blockPosition().distSqr(t.center) > 90 * 90) { pause(100); return false; }
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe t = m.tribe();
            return timeout > 0 && t != null && sl().getGameTime() <= t.expUntil && m.level().dimension() == Level.OVERWORLD;
        }

        @Override
        public void start() {
            timeout = 900;
            phase = 0;
            m.setStatus("Heading to the Nether portal");
        }

        @Override
        public void tick() {
            timeout--;
            Tribe t = m.tribe();
            if (t == null) return;
            BlockPos front = t.center.offset(4, 0, 3);
            if (phase == 0) {
                if (m.blockPosition().distSqr(front) < 5.0) phase = 1;
                else if (m.getNavigation().isDone()) walk(m, front, 1.0);
            } else {
                // step into the portal frame (inside the obsidian ring)
                m.getMoveControl().setWantedPosition(t.center.getX() + 4.5, t.center.getY() + 1.0, t.center.getZ() + 5.5, 1.0);
            }
        }

        @Override
        public void stop() {
            m.setStatus("");
            pause(200);
        }
    }

    /** Anyone who is in the Nether explores for a while, then walks back to the portal they came from. */
    public static class NetherGoal extends Base {
        private BlockPos arrival, portal;
        private int age;

        public NetherGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            return server() && m.level().dimension() == Level.NETHER && ready();
        }

        @Override
        public boolean canContinueToUse() { return m.level().dimension() == Level.NETHER; }

        @Override
        public void start() {
            if (arrival == null) arrival = m.blockPosition();
        }

        @Override
        public void tick() {
            age = m.dimTicks();
            ServerLevel level = sl();
            if (age < 2400) { // explore
                m.setStatus("Exploring the Nether");
                if (m.getNavigation().isDone()) {
                    int x = m.getBlockX() + m.getRandom().nextInt(41) - 20;
                    int z = m.getBlockZ() + m.getRandom().nextInt(41) - 20;
                    m.getNavigation().moveTo(x + 0.5, m.getY(), z + 0.5, 1.0);
                }
                return;
            }
            m.setStatus("Returning to the portal");
            if (age > 6000) { // lost in the Nether
                Tribe t = m.tribe();
                if (t != null) {
                    t.pop = Math.max(0, t.pop - 1);
                    TribeLog.add(level, t.name, m.getName().getString() + " was lost in the Nether");
                }
                m.discard();
                return;
            }
            if (portal == null && age % 40 == 0 && arrival != null) {
                for (BlockPos p : BlockPos.betweenClosed(arrival.offset(-8, -4, -8), arrival.offset(8, 6, 8))) {
                    if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) { portal = p.immutable(); break; }
                }
            }
            if (portal != null) {
                if (m.blockPosition().distSqr(portal) > 9.0) {
                    if (m.getNavigation().isDone()) walk(m, portal, 1.0);
                } else {
                    m.getMoveControl().setWantedPosition(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5, 1.0);
                }
            } else if (arrival != null && m.getNavigation().isDone()) {
                walk(m, arrival, 1.0);
            }
        }
    }

    // ================= the storehouse =================
    /**
     * Visits the storehouse: takes the tool needed for the current task, takes food rations when hurt,
     * and drops off everything he has collected. Nobody carries tools around all day.
     */
    public static class StoreGoal extends Base {
        private int timeout;
        private BlockPos inside;

        public StoreGoal(TribesmanEntity m) { super(m); }

        private String why(Tribe t) {
            if (!m.hasTool()) return "Fetching " + TribesmanEntity.toolName(TribesmanEntity.toolForTask(m.task())) + " from the storehouse";
            if (m.bagSize() >= 8) return "Storing " + m.bagSize() + " items in the storehouse";
            if (m.bagSize() > 0 && resting(m)) return "Storing items before resting";
            if (m.rations() == 0 && m.getHealth() < m.getMaxHealth() * 0.8f && t.food >= 6) return "Getting food from the storehouse";
            return null;
        }

        @Override
        public boolean canUse() {
            if (!TribeConfig.STORAGE_TRIPS || !server() || !ready() || m.getTarget() != null || m.isCaptive()) return false;
            Tribe t = m.tribe();
            if (t == null || !t.hasStore) return false;
            if (why(t) == null) { pause(60); return false; }
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return inside != null && timeout > 0 && m.getTarget() == null;
        }

        @Override
        public void start() {
            Tribe t = m.tribe();
            timeout = 1200;
            if (t == null) return;
            inside = new BlockPos(t.sx + 2, t.sy, t.sz + 2);
            String w = why(t);
            m.setStatus(w == null ? "Visiting the storehouse" : w);
            walk(m, inside, 1.0);
        }

        @Override
        public void tick() {
            timeout--;
            Tribe t = m.tribe();
            if (t == null || inside == null) return;
            double dx = m.getX() - (inside.getX() + 0.5), dz = m.getZ() - (inside.getZ() + 0.5);
            if (dx * dx + dz * dz < 6.0 && Math.abs(m.getY() - inside.getY()) < 3.0) {
                ServerLevel level = sl();
                m.getNavigation().stop();
                m.swing(InteractionHand.MAIN_HAND);
                int n = m.deposit(t);
                if (!m.hasTool()) m.takeTool();
                if (m.rations() < 2 && t.food >= 30) { t.food -= 4; m.setRations(2); }
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, m.getX(), m.getEyeY(), m.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
                if (n > 0) TribeData.get(level).setDirty();
                inside = null;
                pause(40);
            } else if (m.getNavigation().isDone()) {
                if (timeout < 600) { // cannot get in: do the job anyway
                    if (!m.hasTool()) m.takeTool();
                    TribeLog.add(sl(), m.getName().getString(), "could not reach the storehouse");
                    inside = null;
                    pause(200);
                } else {
                    walk(m, inside, 1.0);
                }
            }
        }

        @Override
        public void stop() {
            inside = null;
            m.setStatus("");
        }
    }

    // ================= emergencies =================
    /** On fire or in lava: run to water, or at least away from the fire. */
    public static class FireGoal extends Base {
        private BlockPos water;
        private int timeout;

        public FireGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            return TribeConfig.INSTINCTS && server() && (m.isOnFire() || m.isInLava());
        }

        @Override
        public boolean canContinueToUse() {
            return timeout > 0 && (m.isOnFire() || m.isInLava());
        }

        @Override
        public void start() {
            timeout = 200;
            ServerLevel level = sl();
            m.setStatus("ON FIRE - looking for water");
            Tribe t = m.tribe();
            if (t != null) t.markDanger(m.blockPosition(), 3);
            water = null;
            double best = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(m.blockPosition().offset(-8, -2, -8), m.blockPosition().offset(8, 2, 8))) {
                if (!level.getFluidState(p).is(FluidTags.WATER)) continue;
                double d = p.distSqr(m.blockPosition());
                if (d < best) { best = d; water = p.immutable(); }
            }
            if (water != null) walk(m, water, 1.5);
        }

        @Override
        public void tick() {
            timeout--;
            if (m.isInLava()) m.getJumpControl().jump();
            if (m.getNavigation().isDone()) {
                if (water != null) {
                    walk(m, water, 1.5);
                } else {
                    BlockPos from = m.blockPosition();
                    walk(m, from.offset(m.getRandom().nextInt(13) - 6, 0, m.getRandom().nextInt(13) - 6), 1.5);
                }
            }
        }

        @Override
        public void stop() {
            water = null;
            m.setStatus("");
        }
    }

    /** Badly hurt with an enemy nearby: run home to the warriors instead of fighting on. */
    public static class FleeGoal extends Base {
        private int timeout;

        public FleeGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!TribeConfig.INSTINCTS || !server() || !ready()) return false;
            float frac = m.getHealth() / m.getMaxHealth();
            if (frac >= (m.isFighter() ? 0.25f : 0.45f)) return false;
            if (m.getRole() == TribesmanEntity.TRADER && m.tribe() == null) return false;
            for (LivingEntity e : m.level().getEntitiesOfClass(LivingEntity.class, m.getBoundingBox().inflate(10.0),
                    x -> x != m && x.isAlive() && (x instanceof net.minecraft.world.entity.monster.Monster
                            || (x instanceof TribesmanEntity o && o.getTribeId() != m.getTribeId())))) {
                return true;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return timeout > 0 && !m.getNavigation().isDone() && m.getHealth() < m.getMaxHealth() * 0.6f;
        }

        @Override
        public void start() {
            timeout = 300;
            m.setTarget(null);
            m.setStatus("Fleeing!");
            Tribe t = m.tribe();
            if (t != null) walk(m, t.center, 1.35);
        }

        @Override
        public void tick() { timeout--; }

        @Override
        public void stop() {
            m.setStatus("");
            pause(60);
        }
    }

    /** Warriors and archers rush to the place where the village was attacked. */
    public static class RallyGoal extends Base {
        private BlockPos spot;
        private int timeout;

        public RallyGoal(TribesmanEntity m) { super(m); }

        @Override
        public boolean canUse() {
            if (!TribeConfig.INSTINCTS || !m.isFighter() || m.getRole() == TribesmanEntity.ARCHER && m.post() != null
                    || !server() || !ready() || m.getTarget() != null) return false;
            Tribe t = m.tribe();
            if (t == null || t.alarmUntil <= sl().getGameTime()) { pause(40); return false; }
            BlockPos at = new BlockPos(t.alarmX, m.getBlockY(), t.alarmZ);
            if (m.blockPosition().distSqr(at) < 36.0) { pause(20); return false; }
            spot = at;
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            Tribe t = m.tribe();
            return spot != null && timeout > 0 && m.getTarget() == null && t != null && t.alarmUntil > sl().getGameTime()
                    && !m.getNavigation().isDone();
        }

        @Override
        public void start() {
            timeout = 300;
            m.setStatus("Rushing to the alarm");
            walk(m, spot, 1.25);
        }

        @Override
        public void tick() { timeout--; }

        @Override
        public void stop() {
            spot = null;
            m.setStatus("");
            pause(20);
        }
    }
}
