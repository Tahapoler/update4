package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * Real, physical mines. Each mine is a spiral staircase that goes down to diamond level,
 * then a long spine tunnel with side branches (strip mining). State lives in Tribe.mine[].
 */
public class TribeMine {
    public static final int SIZE = 14;
    static final int READY = 0, X = 1, Y = 2, Z = 3, DIR = 4, MODE = 5, RUN = 6, SPINE = 7,
            BR = 8, BX = 9, BY = 10, BZ = 11, COUNT = 12, MINES = 13;
    static final int[] DX = {1, 0, -1, 0};
    static final int[] DZ = {0, 1, 0, -1};
    static final int BOTTOM = -56;

    public static int maxMines(Tribe t) { return 1 + t.level / 3; }

    public static int minesStarted(Tribe t) { return t.mine[MINES]; }
    public static int cellsDug(Tribe t) { return t.mine[COUNT]; }
    public static boolean ready(Tribe t) { return t.mine[READY] == 1; }

    /** Fixed mine entrance positions around the tribe (building slots keep away from these). */
    public static int[] entrance(Tribe t, int idx) {
        double ang = Math.toRadians(20 + idx * 47);
        int r = 34;
        return new int[]{t.center.getX() + (int) Math.round(Math.cos(ang) * r),
                t.center.getZ() + (int) Math.round(Math.sin(ang) * r)};
    }

    public static boolean blocks(Tribe t, int cx, int cz) {
        for (int i = 0; i < 10; i++) {
            int[] e = entrance(t, i);
            if (Math.abs(e[0] - cx) < 8 && Math.abs(e[1] - cz) < 8) return true;
        }
        return false;
    }

    public static BlockPos head(Tribe t) {
        return new BlockPos(t.mine[X], t.mine[Y], t.mine[Z]);
    }

    /** Returns the current dig face, creating a new mine entrance when needed. Null = nothing to dig. */
    public static BlockPos face(ServerLevel level, Tribe t) {
        if (!ensure(level, t)) return null;
        return head(t);
    }

    public static boolean ensure(ServerLevel level, Tribe t) {
        if (t.mine[READY] == 1) return true;
        if (!t.founded || t.mine[MINES] >= maxMines(t)) return false;
        int idx = t.mine[MINES];
        int[] e = entrance(t, idx);
        if (!level.hasChunksAt(new BlockPos(e[0] - 6, 64, e[1] - 6), new BlockPos(e[0] + 6, 64, e[1] + 6))) return false;
        int y = TribeManager.groundY(level, e[0], e[1]);
        BlockPos g = new BlockPos(e[0], y - 1, e[1]);
        if (y <= level.getSeaLevel() || !level.getFluidState(g).isEmpty() || !level.getFluidState(g.above()).isEmpty()) {
            t.mine[MINES]++; // bad site (water) - skip it
            return false;
        }
        buildEntrance(level, e[0], y, e[1]);
        t.mine[READY] = 1;
        t.mine[X] = e[0];
        t.mine[Y] = y;
        t.mine[Z] = e[1];
        t.mine[DIR] = idx & 3;
        t.mine[MODE] = 0;
        t.mine[RUN] = 0;
        t.mine[SPINE] = 0;
        t.mine[BR] = 0;
        return true;
    }

    static void buildEntrance(ServerLevel level, int x, int y, int z) {
        TribeManager.flatten(level, new BlockPos(x - 2, y, z - 2), 5, 5);
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(x + dx, y + 3, z + dz), plank, 3);
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                    for (int dy = 0; dy < 3; dy++) level.setBlock(new BlockPos(x + dx, y + dy, z + dz), cobble, 3);
                }
            }
        }
        level.setBlock(new BlockPos(x - 1, y, z - 1), Blocks.LANTERN.defaultBlockState(), 3);
    }

    /** Dig up to `cells` new tunnel cells (each cell is a 3x3 cross-section). Returns how many were dug. */
    public static int dig(ServerLevel level, Tribe t, int cells) {
        int done = 0;
        for (int i = 0; i < cells; i++) {
            if (!ensure(level, t)) break;
            int[] snap = t.mine.clone();
            int[] n = next(t);
            if (n == null) {
                t.mine[READY] = 0;
                t.mine[MINES]++;
                TribeManager.notifyNear(level, t.center, "§7[" + t.name + "] finished mine #" + t.mine[MINES] + ".");
                break;
            }
            BlockPos feet = new BlockPos(n[0], n[1], n[2]);
            if (!level.hasChunkAt(feet)) {
                System.arraycopy(snap, 0, t.mine, 0, snap.length);
                break;
            }
            clearCell(level, t, feet, n[3]);
            done++;
        }
        return done;
    }

    /** Advances the mine plan by one cell. Returns {x, y, z, orientation} or null when the mine is finished. */
    static int[] next(Tribe t) {
        int[] m = t.mine;
        int mode = m[MODE];
        int dir = m[DIR];
        if (mode == 0) { // spiral staircase, 12 steps per flight, turning right
            if (m[Y] <= BOTTOM) {
                m[MODE] = 1;
                m[SPINE] = 0;
                return next(t);
            }
            if (m[RUN] >= 12) {
                m[DIR] = (m[DIR] + 1) & 3;
                m[RUN] = 0;
                dir = m[DIR];
            }
            m[X] += DX[dir];
            m[Z] += DZ[dir];
            m[Y] -= 1;
            m[RUN]++;
            return new int[]{m[X], m[Y], m[Z], dir};
        }
        if (mode == 1) { // spine tunnel
            if (m[SPINE] >= 24) {
                m[MODE] = 4;
                return null;
            }
            m[X] += DX[dir];
            m[Z] += DZ[dir];
            m[SPINE]++;
            if (m[SPINE] % 4 == 0) {
                m[BX] = m[X];
                m[BY] = m[Y];
                m[BZ] = m[Z];
                m[MODE] = 2;
                m[BR] = 0;
            }
            return new int[]{m[X], m[Y], m[Z], dir};
        }
        if (mode == 2 || mode == 3) { // side branches (left, then right)
            int sgn = mode == 2 ? 1 : -1;
            if (m[BR] >= 16) {
                m[X] = m[BX];
                m[Y] = m[BY];
                m[Z] = m[BZ];
                m[BR] = 0;
                m[MODE] = mode == 2 ? 3 : 1;
                return next(t);
            }
            m[X] += sgn * (-DZ[dir]);
            m[Z] += sgn * DX[dir];
            m[BR]++;
            return new int[]{m[X], m[Y], m[Z], (dir + 1) & 3};
        }
        return null;
    }

    static void clearCell(ServerLevel level, Tribe t, BlockPos feet, int orient) {
        int lx = -DZ[orient], lz = DX[orient];
        for (int w = -1; w <= 1; w++) {
            BlockPos floor = feet.offset(lx * w, -1, lz * w);
            if (level.hasChunkAt(floor)) {
                BlockState fs = level.getBlockState(floor);
                if (fs.isAir() || !fs.getFluidState().isEmpty()) level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
            for (int h = 0; h < 3; h++) {
                BlockPos p = feet.offset(lx * w, h, lz * w);
                if (p.getY() <= level.getMinBuildHeight() + 1) continue;
                if (!level.hasChunkAt(p)) continue;
                mineBlock(level, t, p);
            }
        }
        if (t.mine[COUNT]++ % 6 == 0) {
            BlockPos tp = feet.offset(lx, 0, lz);
            BlockPos below = tp.below();
            if (level.getBlockState(tp).isAir() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                level.setBlock(tp, Blocks.TORCH.defaultBlockState(), 3);
            }
        }
    }

    static void mineBlock(ServerLevel level, Tribe t, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return;
        if (!s.getFluidState().isEmpty()) {
            level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 3);
            return;
        }
        if (s.is(Blocks.BEDROCK) || s.hasBlockEntity() || s.getDestroySpeed(level, p) < 0) return;
        collect(t, s);
        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        seal(level, p);
        for (Direction d : Direction.values()) {
            BlockPos q = p.relative(d);
            if (isOre(level.getBlockState(q))) vein(level, t, q);
        }
    }

    /** Mines a whole connected ore vein (up to 24 blocks). */
    static void vein(ServerLevel level, Tribe t, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        int n = 0;
        while (!queue.isEmpty() && n < 24) {
            BlockPos p = queue.poll();
            if (!level.hasChunkAt(p)) continue;
            BlockState s = level.getBlockState(p);
            if (!isOre(s)) continue;
            collect(t, s);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            seal(level, p);
            n++;
            for (Direction d : Direction.values()) {
                BlockPos r = p.relative(d);
                if (isOre(level.getBlockState(r))) queue.add(r.immutable());
            }
        }
    }

    /** Plug lava/water and falling blocks next to a freshly opened block. */
    static void seal(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (!level.hasChunkAt(n)) continue;
            BlockState ns = level.getBlockState(n);
            if (!ns.getFluidState().isEmpty()) {
                level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
            } else if (d == Direction.UP && ns.getBlock() instanceof FallingBlock) {
                level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    static boolean isOre(BlockState s) {
        return s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.GOLD_ORES)
                || s.is(BlockTags.EMERALD_ORES) || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES);
    }

    static void collect(Tribe t, BlockState s) {
        if (s.is(BlockTags.DIAMOND_ORES)) t.diamond += 3;
        else if (s.is(BlockTags.IRON_ORES)) t.iron += 4;
        else if (s.is(BlockTags.GOLD_ORES)) t.gold += 3;
        else if (s.is(BlockTags.EMERALD_ORES)) t.wealth += 25;
        else if (s.is(BlockTags.COAL_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)) t.wealth += 4;
        else t.stone += 1;
    }
}
