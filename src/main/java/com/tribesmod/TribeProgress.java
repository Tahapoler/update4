package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The "brain" of a tribe: technology stages that lead all the way to the Ender Dragon,
 * plus empire expansion (colonies).
 *
 * Stages: 0 Stone -> 1 Iron -> 2 Diamond -> 3 Nether Campaign -> 4 Stronghold Quest
 *         -> 5 End Siege -> 6 EMPIRE (dragon slain)
 */
public class TribeProgress {

    static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    static int cooldown(int ticks, int min) { return (int) Math.max(min, ticks / TribeConfig.GROWTH); }

    // ================= simulated mining + tech =================
    static void tick(ServerLevel level, TribeData data, Tribe t, RandomSource rnd, boolean near) {
        double v = (near ? 0.35 : 1.0) * TribeConfig.GROWTH * (t.trait == 2 ? 1.5 : 1.0);
        int miners = Math.max(1, t.pop / 5);
        t.stone += (int) Math.ceil(miners * 3 * v);
        if (t.level >= 2) t.iron += (int) Math.ceil(miners * (0.5 + t.level / 6.0) * v);
        if (t.level >= 4 && rnd.nextInt(2) == 0) t.gold += (int) Math.ceil(miners * 0.5 * v);
        if (t.level >= 4 && rnd.nextInt(3) == 0) t.diamond += (int) Math.ceil(miners * 0.35 * v);
        if (t.stone > 400) { t.stone -= 200; t.wealth += 30; }
        t.iron = Math.min(t.iron, 3000);
        t.gold = Math.min(t.gold, 2000);
        t.diamond = Math.min(t.diamond, 2000);
        if (t.parent != 0) return; // vassals share the technology of their overlord
        advance(level, data, t, rnd);
    }

    static void setStage(ServerLevel level, Tribe t, int s, String msg) {
        t.stage = s;
        t.cool = 0;
        TribeManager.notifyNear(level, t.center, "§b[" + t.name + "] " + msg);
    }

    static void loseMen(Tribe t, int n) { t.pop = Math.max(2, t.pop - n); }

    static void advance(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        switch (t.stage) {
            case 0 -> {
                if (t.level >= 2 && t.iron >= 40) {
                    t.iron -= 20;
                    setStage(level, t, 1, "mastered IRON! Iron tools and armor for everyone.");
                }
            }
            case 1 -> {
                if (t.level >= 4 && t.diamond >= 20 && t.iron >= 50) {
                    t.diamond -= 15;
                    t.iron -= 30;
                    setStage(level, t, 2, "found DIAMONDS! The Diamond Age begins.");
                }
            }
            case 2 -> {
                if (t.level >= 6 && t.diamond >= 14 && t.gold >= 20 && t.strength() >= 45) {
                    t.diamond -= 14;
                    t.gold -= 20;
                    setStage(level, t, 3, "opened a gate to the NETHER! Expeditions are marching out.");
                }
            }
            case 3 -> {
                if (t.cool > 0) {
                    t.cool--;
                } else {
                    t.cool = cooldown(18, 3);
                    double chance = clamp(t.strength() / (50 + t.level * 4), 0.25, 0.95);
                    if (rnd.nextDouble() < chance) {
                        int b = 3 + rnd.nextInt(4), p = 2 + rnd.nextInt(3);
                        t.blaze += b;
                        t.pearls += p;
                        t.wealth += 25;
                        TribeManager.notifyNear(level, t.center, "§6[" + t.name + "] Nether expedition returned: +"
                                + b + " blaze rods, +" + p + " ender pearls");
                    } else {
                        loseMen(t, 1 + t.pop / 15);
                    }
                }
                if (t.level >= 8 && t.blaze >= 12 && t.pearls >= 12) {
                    t.blaze -= 12;
                    t.pearls -= 12;
                    setStage(level, t, 4, "crafted 12 Eyes of Ender and set out to find the Stronghold!");
                }
            }
            case 4 -> {
                if (t.cool > 0) {
                    t.cool--;
                } else if (t.level >= 10) {
                    t.cool = cooldown(12, 3);
                    if (rnd.nextDouble() < clamp(t.strength() / 110.0, 0.15, 0.9)) {
                        setStage(level, t, 5, "found the Stronghold and lit the END PORTAL! The great army gathers...");
                    } else {
                        loseMen(t, 1 + t.pop / 20);
                    }
                }
            }
            case 5 -> {
                if (t.cool > 0) {
                    t.cool--;
                } else if (t.level >= 12 && t.strength() >= 140) {
                    t.cool = cooldown(24, 6);
                    TribeManager.notifyNear(level, t.center, "§5[" + t.name + "] is assaulting the ENDER DRAGON in The End!");
                    double chance = clamp((t.strength() - 120) / 200.0, 0.03, 0.9);
                    if (rnd.nextDouble() < chance) {
                        slayDragon(level, data, t);
                    } else {
                        int loss = Math.max(3, t.pop / 5);
                        loseMen(t, loss);
                        t.wealth = (int) (t.wealth * 0.85);
                        TribeManager.notifyNear(level, t.center, "§c[" + t.name + "] The dragon won this time... lost "
                                + loss + " warriors. They will return stronger.");
                    }
                }
            }
            default -> { }
        }
    }

    // ================= dragon =================
    static void slayDragon(ServerLevel level, TribeData data, Tribe t) {
        t.stage = 6;
        t.cool = 0;
        t.wealth += 500;
        t.food += 300;
        t.gold += 100;
        t.diamond += 60;
        boolean first = data.dragonsSlain == 0;
        data.dragonsSlain++;
        MinecraftServer server = level.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("§5§l[DRAGON SLAIN] §d" + t.name
                    + " has killed the Ender Dragon and founded an EMPIRE!"));
        }
        if (first && TribeConfig.REAL_DRAGON_KILL) killRealDragon(server, data);
    }

    static void killRealDragon(MinecraftServer server, TribeData data) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            data.pendingDragons = 1;
            return;
        }
        if (!end.players().isEmpty()) return; // a player is in the End right now - the victory stays symbolic
        boolean found = false;
        for (EnderDragon d : end.getEntitiesOfClass(EnderDragon.class, new AABB(-500, -64, -500, 500, 320, 500))) {
            if (d.isAlive()) {
                d.kill();
                found = true;
            }
        }
        if (!found) data.pendingDragons = 1; // the next dragon that appears is already dead
    }

    public static void onEntityLoad(Entity e, ServerLevel world) {
        if (!(e instanceof EnderDragon) || world.dimension() != Level.END) return;
        TribeData data = TribeData.get(world);
        if (data.pendingDragons <= 0) return;
        data.pendingDragons = 0;
        data.setDirty();
        final EnderDragon d = (EnderDragon) e;
        world.getServer().execute(() -> {
            if (d.isAlive()) {
                d.kill();
                for (ServerPlayer p : world.players()) {
                    p.sendSystemMessage(Component.literal("§5[Tribes] The Ender Dragon was already slain by a tribe's army."));
                }
            }
        });
    }

    // ================= empire expansion =================
    static void expand(ServerLevel level, TribeData data, Tribe t, RandomSource rnd) {
        if (t.parent != 0 || !t.founded || t.level < 3) return;
        if (data.tribes.size() >= TribeConfig.MAX_TRIBES) return;
        int count = 0;
        for (Tribe o : data.tribes.values()) if (o.parent == t.id) count++;
        int max = 2 + t.level / 2 + (t.stage >= 6 ? 4 : 0);
        if (count >= max || t.pop < t.popCap() * 0.6 || t.wealth < 100 || rnd.nextInt(2) != 0) return;

        for (int a = 0; a < 6; a++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int dist = 95 + rnd.nextInt(60);
            int x = t.center.getX() + (int) (Math.cos(ang) * dist);
            int z = t.center.getZ() + (int) (Math.sin(ang) * dist);
            boolean ok = true;
            for (Tribe o : data.tribes.values()) {
                double dx = o.center.getX() - x, dz = o.center.getZ() - z;
                if (dx * dx + dz * dz < 80.0 * 80.0) { ok = false; break; }
            }
            if (!ok) continue;
            Tribe c = new Tribe();
            c.id = data.nextId++;
            c.name = t.name.split("-")[0] + "-" + (count + 2);
            c.center = new BlockPos(x, 64, z);
            c.parent = t.id;
            c.founded = false;
            c.pop = 4;
            c.food = 60;
            c.wood = 30;
            c.stage = t.stage;
            c.trait = t.trait;
            t.pop = Math.max(2, t.pop - 3);
            t.wealth -= 60;
            data.tribes.put(c.id, c);
            TribeManager.notifyNear(level, t.center, "§6[EMPIRE] " + t.name + " founded a new colony: " + c.name);
            return;
        }
    }
}
