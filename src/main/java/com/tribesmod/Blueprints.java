package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

/**
 * Every building is a deterministic list of block placements. Builders place them one by one;
 * the founding village uses the very same lists but places everything at once.
 * Origin "o" is the south-west corner of the footprint, at ground level (floor is at o.y - 1).
 */
public final class Blueprints {
    private Blueprints() {}

    public static final int HUT = 0, FARM = 1, TOWER = 2, FORGE = 3, WELL = 4, WALL = 5, PEN = 6, STORE = 7,
            CASTLE = 8, BIGFARM = 9;
    public static final String[] NAMES = {"house", "farm", "watchtower", "forge", "well", "city wall", "animal pen",
            "storehouse", "castle", "large farm"};
    /** The storehouse comes early: the first four buildings are house, storehouse, farm, house. */
    private static final int[] PATTERN = {HUT, STORE, FARM, HUT, PEN, FORGE, TOWER, HUT};

    /** Big projects: builders must follow the work instead of standing at one spot. */
    public static boolean large(int type) { return type == WALL || type == CASTLE || type == BIGFARM; }

    /**
     * One block to place. If surface is true, pos.y is only an offset above the ground height at (x, z),
     * which is looked up when the block is placed (used by the city wall, which follows the terrain).
     */
    public record Placement(BlockPos pos, BlockState state, boolean surface) {
        public Placement(BlockPos pos, BlockState state) { this(pos, state, false); }
    }

    /** The real world position of a placement, or null if its chunk is not loaded. */
    public static BlockPos resolve(ServerLevel level, Placement p) {
        BlockPos pos = p.pos();
        if (!p.surface()) return level.hasChunkAt(pos) ? pos : null;
        BlockPos probe = new BlockPos(pos.getX(), 64, pos.getZ());
        if (!level.hasChunkAt(probe)) return null;
        return new BlockPos(pos.getX(), TribeManager.groundY(level, pos.getX(), pos.getZ()) + pos.getY(), pos.getZ());
    }

    /** Surface blocks (walls) may only replace air, plants, leaves and logs - never other structures. */
    public static boolean canOverwrite(ServerLevel level, Placement p, BlockPos pos) {
        if (!p.surface()) return true;
        BlockState cur = level.getBlockState(pos);
        if (!level.getFluidState(pos.below()).isEmpty()) return false;
        return cur.isAir() || cur.canBeReplaced() || cur.is(BlockTags.LEAVES) || cur.is(BlockTags.LOGS);
    }

    public static int slotType(int slot) {
        return PATTERN[Math.floorMod(slot, PATTERN.length)];
    }

    public static String name(int type) {
        return NAMES[Math.max(0, Math.min(NAMES.length - 1, type))];
    }

    // ---------- materials ----------
    /** 0 = free, 1 = wood, 2 = stone. */
    public static int costKind(BlockState s) {
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(BlockTags.WOODEN_DOORS)
                || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_FENCES)) return 1;
        if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE_BRICKS) || s.is(BlockTags.WALLS)) return 2;
        return 0;
    }

    /** One unit of tribe material pays for three blocks. */
    public static boolean pay(Tribe t, int kind, int index) {
        if (kind == 0 || index % 3 != 0) return true;
        if (kind == 2 && t.stone >= 1) { t.stone--; return true; }
        if (t.wood >= 1) { t.wood--; return true; }
        return false;
    }

    public static void applyAll(ServerLevel level, List<Placement> list, int from) {
        for (int i = from; i < list.size(); i++) {
            Placement p = list.get(i);
            BlockPos pos = resolve(level, p);
            if (pos == null || !canOverwrite(level, p, pos)) continue;
            level.setBlock(pos, p.state(), 3);
        }
    }

    // ---------- helpers ----------
    private static void add(List<Placement> l, BlockPos o, int dx, int dy, int dz, BlockState s) {
        l.add(new Placement(o.offset(dx, dy, dz), s));
    }

    private static BlockState door(DoubleBlockHalf half) {
        return Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, half);
    }

    private static BlockState bed(BedPart part) {
        return Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, part);
    }

    public static List<Placement> make(int type, BlockPos o, boolean instant) {
        List<Placement> l = new ArrayList<>();
        switch (type) {
            case HUT -> hut(l, o);
            case FARM -> farm(l, o, instant);
            case TOWER -> tower(l, o);
            case FORGE -> forge(l, o);
            case WELL -> well(l, o);
            case WALL -> wall(l, o);
            case PEN -> pen(l, o);
            case STORE -> store(l, o);
            case CASTLE -> castle(l, o);
            case BIGFARM -> bigFarm(l, o, instant);
            default -> { }
        }
        return l;
    }

    // ---------- house ----------
    private static void hut(List<Placement> l, BlockPos o) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, -1, dz, plank);
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    if (!edge) continue;
                    boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                    if (dx == 2 && dz == 0 && dy < 2) continue; // doorway
                    BlockState s = corner ? cobble : plank;
                    if (dy == 1 && !corner && ((dz == 2 && (dx == 0 || dx == 4)) || (dx == 2 && dz == 4))) s = pane;
                    add(l, o, dx, dy, dz, s);
                }
            }
        }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, 3, dz, plank);
        add(l, o, 2, 0, 0, door(DoubleBlockHalf.LOWER));
        add(l, o, 2, 1, 0, door(DoubleBlockHalf.UPPER));
        add(l, o, 2, 0, 2, Blocks.LANTERN.defaultBlockState());
        add(l, o, 1, 0, 3, Blocks.CRAFTING_TABLE.defaultBlockState());
        add(l, o, 3, 0, 3, Blocks.BARREL.defaultBlockState());
        add(l, o, 3, 0, 1, bed(BedPart.FOOT));
        add(l, o, 3, 0, 2, bed(BedPart.HEAD));
    }

    // ---------- farm ----------
    private static void farm(List<Placement> l, BlockPos o, boolean instant) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                if (dx == 2 && dz == 2) add(l, o, dx, -1, dz, Blocks.WATER.defaultBlockState());
                else add(l, o, dx, -1, dz, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            }
        }
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                if (dx == 2 && dz == 2) continue;
                BlockState crop = dx == 3 ? Blocks.CARROTS.defaultBlockState()
                        : dx == 4 ? Blocks.POTATOES.defaultBlockState()
                        : Blocks.WHEAT.defaultBlockState();
                int age = instant ? Math.floorMod(o.getX() * 7 + o.getZ() * 11 + dx * 13 + dz * 5, 8) : 0;
                add(l, o, dx, 0, dz, crop.setValue(CropBlock.AGE, age));
            }
        }
    }

    // ---------- watchtower ----------
    private static void tower(List<Placement> l, BlockPos o) {
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, -1, dz, brick);
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    if (edge) {
                        if (dx == 2 && dz == 0 && dy < 2) continue; // doorway
                        BlockState s = brick;
                        if (dy == 4 && ((dz == 2 && (dx == 0 || dx == 4)) || (dx == 2 && dz == 4))) s = pane;
                        add(l, o, dx, dy, dz, s);
                    } else if (dy == 3 || dy == 7) {
                        if (dx == 1 && dz == 2) continue; // ladder hole
                        add(l, o, dx, dy, dz, dy == 3 ? plank : brick);
                    }
                }
            }
        }
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                if (edge && (dx + dz) % 2 == 0) add(l, o, dx, 8, dz, brick);
            }
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
        for (int dy = 0; dy < 8; dy++) add(l, o, 1, dy, 2, ladder);
        add(l, o, 2, 0, 0, door(DoubleBlockHalf.LOWER));
        add(l, o, 2, 1, 0, door(DoubleBlockHalf.UPPER));
        add(l, o, 2, 0, 2, Blocks.LANTERN.defaultBlockState());
        add(l, o, 2, 4, 2, Blocks.LANTERN.defaultBlockState());
        add(l, o, 3, 8, 3, Blocks.LANTERN.defaultBlockState());
    }

    // ---------- forge ----------
    private static void forge(List<Placement> l, BlockPos o) {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, -1, dz, cobble);
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean post = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                    boolean back = dz == 4 && dx >= 1 && dx <= 3;
                    if (post) add(l, o, dx, dy, dz, log);
                    else if (back) add(l, o, dx, dy, dz, cobble);
                }
            }
        }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, 3, dz, plank);
        add(l, o, 1, 0, 3, Blocks.FURNACE.defaultBlockState());
        add(l, o, 2, 0, 3, Blocks.ANVIL.defaultBlockState());
        add(l, o, 3, 0, 3, Blocks.SMITHING_TABLE.defaultBlockState());
        add(l, o, 3, 0, 1, Blocks.CAULDRON.defaultBlockState());
        add(l, o, 1, 0, 1, Blocks.LANTERN.defaultBlockState());
    }

    // ---------- well (3x3) ----------
    private static void well(List<Placement> l, BlockPos o) {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                if (dx == 1 && dz == 1) add(l, o, dx, -1, dz, Blocks.WATER.defaultBlockState());
                else {
                    add(l, o, dx, -1, dz, cobble);
                    add(l, o, dx, 0, dz, cobble);
                }
            }
        }
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int dy = 1; dy <= 2; dy++) {
            add(l, o, 0, dy, 0, fence);
            add(l, o, 2, dy, 0, fence);
            add(l, o, 0, dy, 2, fence);
            add(l, o, 2, dy, 2, fence);
        }
        BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState();
        for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) add(l, o, dx, 3, dz, slab);
    }

    // ---------- city wall (o = village center, follows the terrain) ----------
    public static final int WALL_RADIUS = 27;

    private static void wall(List<Placement> l, BlockPos c) {
        BlockState w = Blocks.COBBLESTONE_WALL.defaultBlockState();
        int r = WALL_RADIUS;
        for (int side = 0; side < 4; side++) {
            for (int i = -r; i <= r; i++) {
                if (Math.abs(i) <= 1) continue; // 3-block wide gate in the middle of every side
                int x = side == 0 ? i : side == 1 ? i : side == 2 ? -r : r;
                int z = side == 0 ? -r : side == 1 ? r : i;
                l.add(new Placement(new BlockPos(c.getX() + x, 0, c.getZ() + z), w, true));
                l.add(new Placement(new BlockPos(c.getX() + x, 1, c.getZ() + z), w, true));
            }
        }
    }

    // ---------- animal pen ----------
    private static void pen(List<Placement> l, BlockPos o) {
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        BlockState gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH);
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                if (!edge) continue;
                add(l, o, dx, 0, dz, (dx == 2 && dz == 0) ? gate : fence);
            }
        }
        add(l, o, 3, 0, 3, Blocks.HAY_BLOCK.defaultBlockState());
    }

    // ---------- storehouse (chests show the tribe's stock) ----------
    private static void store(List<Placement> l, BlockPos o) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, -1, dz, plank);
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                    if (!edge) continue;
                    if (dx == 2 && dz == 0 && dy < 2) continue; // doorway
                    boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                    add(l, o, dx, dy, dz, corner ? log : plank);
                }
            }
        }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) add(l, o, dx, 3, dz, plank);
        add(l, o, 2, 0, 0, door(DoubleBlockHalf.LOWER));
        add(l, o, 2, 1, 0, door(DoubleBlockHalf.UPPER));
        BlockState chest = Blocks.CHEST.defaultBlockState();
        add(l, o, 1, 0, 3, chest);
        add(l, o, 2, 0, 3, chest);
        add(l, o, 3, 0, 3, chest);
        add(l, o, 1, 0, 1, chest);
        add(l, o, 3, 0, 1, Blocks.LANTERN.defaultBlockState());
    }

    // ---------- large irrigated farm (15x15, 2 water channels) ----------
    private static void bigFarm(List<Placement> l, BlockPos o, boolean instant) {
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        BlockState gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.NORTH);
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                if (x == 4 || x == 10) add(l, o, x, -1, z, Blocks.WATER.defaultBlockState());
                else add(l, o, x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            }
        }
        for (int x = 1; x <= 13; x++) {
            for (int z = 1; z <= 13; z++) {
                if (x == 4 || x == 10) continue;
                if (x == 7 && z == 7) continue; // scarecrow
                BlockState crop = (x <= 3) ? Blocks.WHEAT.defaultBlockState()
                        : (x <= 9) ? (z % 2 == 0 ? Blocks.CARROTS.defaultBlockState() : Blocks.POTATOES.defaultBlockState())
                        : Blocks.WHEAT.defaultBlockState();
                int age = instant ? Math.floorMod(o.getX() * 7 + o.getZ() * 11 + x * 13 + z * 5, 8) : 0;
                add(l, o, x, 0, z, crop.setValue(CropBlock.AGE, age));
            }
        }
        add(l, o, 7, 0, 7, Blocks.HAY_BLOCK.defaultBlockState());
        add(l, o, 7, 1, 7, Blocks.CARVED_PUMPKIN.defaultBlockState());
        for (int i = 0; i < 15; i++) {
            add(l, o, i, 0, 0, (i == 7) ? gate : fence);
            add(l, o, i, 0, 14, fence);
            if (i != 0 && i != 14) {
                add(l, o, 0, 0, i, fence);
                add(l, o, 14, 0, i, fence);
            }
        }
        add(l, o, 0, 1, 0, Blocks.LANTERN.defaultBlockState());
        add(l, o, 14, 1, 0, Blocks.LANTERN.defaultBlockState());
    }

    // ---------- the castle (41 x 41, built north of the village) ----------
    public static final int CASTLE_SIZE = 41;
    /** Centre of the jail cell, relative to the castle origin. */
    public static final int JAIL_X = 27, JAIL_Z = 6;

    private static void fill(List<Placement> l, BlockPos o, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) add(l, o, x, y, z, s);
    }

    private static void castle(List<Placement> l, BlockPos o) {
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
        BlockState lantern = Blocks.LANTERN.defaultBlockState();
        int n = CASTLE_SIZE - 1; // 40

        // paved road from the gate to the keep
        for (int z = 2; z <= 12; z++) add(l, o, 20, -1, z, Blocks.DIRT_PATH.defaultBlockState());

        // curtain walls: two blocks thick, 7 high, battlements on top, a 5-wide gate in the south
        for (int dy = 0; dy <= 6; dy++) {
            for (int x = 0; x <= n; x++) {
                for (int z = 0; z <= n; z++) {
                    boolean wall = x < 2 || x > n - 2 || z < 2 || z > n - 2;
                    if (!wall) continue;
                    if (z < 2 && x >= 18 && x <= 22 && dy < 5) continue; // gate
                    add(l, o, x, dy, z, brick);
                }
            }
        }
        for (int x = 0; x <= n; x++) {
            for (int z = 0; z <= n; z++) {
                boolean outer = x == 0 || x == n || z == 0 || z == n;
                if (outer && (x + z) % 2 == 0) add(l, o, x, 7, z, brick);
            }
        }
        add(l, o, 17, 4, 2, lantern);
        add(l, o, 23, 4, 2, lantern);

        // four corner towers (7x7, 14 high, ladders inside)
        int[][] corners = {{0, 0}, {n - 6, 0}, {0, n - 6}, {n - 6, n - 6}};
        for (int[] c : corners) {
            int x0 = c[0], z0 = c[1];
            boolean west = x0 == 0;
            int lx = west ? x0 + 1 : x0 + 5, lz = z0 + 3;
            for (int dy = 0; dy <= 13; dy++) {
                for (int x = x0; x <= x0 + 6; x++) {
                    for (int z = z0; z <= z0 + 6; z++) {
                        boolean ring = x == x0 || x == x0 + 6 || z == z0 || z == z0 + 6;
                        if (ring) {
                            int doorX = west ? x0 + 6 : x0;
                            if (x == doorX && z == z0 + 3 && dy < 2) continue; // door to the courtyard
                            boolean win = (dy == 4 || dy == 9) && !((x == x0 || x == x0 + 6) && (z == z0 || z == z0 + 6)) && (x + z) % 2 == 1;
                            add(l, o, x, dy, z, win ? pane : brick);
                        } else if (dy == 5 || dy == 10 || dy == 13) {
                            if (x == lx && z == lz) continue; // ladder hole
                            add(l, o, x, dy, z, dy == 13 ? brick : plank);
                        }
                    }
                }
            }
            for (int x = x0; x <= x0 + 6; x++) {
                for (int z = z0; z <= z0 + 6; z++) {
                    boolean ring = x == x0 || x == x0 + 6 || z == z0 || z == z0 + 6;
                    if (ring && (x + z) % 2 == 0) add(l, o, x, 14, z, brick);
                }
            }
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, west ? Direction.EAST : Direction.WEST);
            for (int dy = 0; dy <= 13; dy++) add(l, o, lx, dy, lz, ladder);
            add(l, o, lx + (west ? 1 : -1), 0, lz, lantern);
        }

        // the keep (15x15, 15 high) in the middle
        int k0 = 13, k1 = 27;
        for (int dy = 0; dy <= 14; dy++) {
            for (int x = k0; x <= k1; x++) {
                for (int z = k0; z <= k1; z++) {
                    boolean ring = x == k0 || x == k1 || z == k0 || z == k1;
                    if (ring) {
                        if (z == k0 && x >= 19 && x <= 21 && dy < 3) continue; // great door (open arch)
                        boolean corner = (x == k0 || x == k1) && (z == k0 || z == k1);
                        boolean win = (dy == 4 || dy == 9 || dy == 12) && !corner && ((x % 4 == 1) || (z % 4 == 1)) && !(z == k0 && dy < 6);
                        add(l, o, x, dy, z, win ? pane : brick);
                    } else if (dy == 5 || dy == 10 || dy == 14) {
                        if (x == 14 && z == 20) continue; // ladder hole
                        add(l, o, x, dy, z, dy == 14 ? brick : plank);
                    }
                }
            }
        }
        for (int x = k0; x <= k1; x++) {
            for (int z = k0; z <= k1; z++) {
                boolean ring = x == k0 || x == k1 || z == k0 || z == k1;
                if (ring && (x + z) % 2 == 0) add(l, o, x, 15, z, brick);
            }
        }
        BlockState kladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
        for (int dy = 0; dy <= 14; dy++) add(l, o, 14, dy, 20, kladder);
        // throne hall: carpet, throne, lanterns, treasury chests
        for (int z = 14; z <= 24; z++) for (int x = 19; x <= 21; x++) add(l, o, x, 0, z, Blocks.RED_CARPET.defaultBlockState());
        add(l, o, 20, 0, 26, Blocks.GOLD_BLOCK.defaultBlockState());
        add(l, o, 16, 0, 15, lantern);
        add(l, o, 24, 0, 15, lantern);
        add(l, o, 16, 0, 25, lantern);
        add(l, o, 24, 0, 25, lantern);
        add(l, o, 15, 0, 26, Blocks.CHEST.defaultBlockState());
        add(l, o, 25, 0, 26, Blocks.CHEST.defaultBlockState());
        add(l, o, 16, 6, 16, lantern);
        add(l, o, 24, 6, 24, lantern);

        // the jail (7x7) in the south-east corner of the courtyard
        int j0 = JAIL_X - 3, jz0 = JAIL_Z - 3;
        for (int x = j0; x <= j0 + 6; x++) for (int z = jz0; z <= jz0 + 6; z++) add(l, o, x, -1, z, brick);
        for (int dy = 0; dy <= 3; dy++) {
            for (int x = j0; x <= j0 + 6; x++) {
                for (int z = jz0; z <= jz0 + 6; z++) {
                    boolean ring = x == j0 || x == j0 + 6 || z == jz0 || z == jz0 + 6;
                    if (!ring) continue;
                    if (x == j0 + 3 && z == jz0 && dy < 2) continue; // door
                    boolean corner = (x == j0 || x == j0 + 6) && (z == jz0 || z == jz0 + 6);
                    boolean bars = dy == 2 && !corner && (x + z) % 2 == 0;
                    add(l, o, x, dy, z, bars ? Blocks.IRON_BARS.defaultBlockState() : brick);
                }
            }
        }
        for (int x = j0; x <= j0 + 6; x++) for (int z = jz0; z <= jz0 + 6; z++) add(l, o, x, 4, z, brick);
        add(l, o, j0 + 3, 0, jz0, Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        add(l, o, j0 + 3, 1, jz0, Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        add(l, o, j0 + 3, 0, jz0 + 4, lantern);
    }
}
