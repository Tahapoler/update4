package com.tribesmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.TreeMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TribesCommands {
    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx, Commands.CommandSelection env) {
        d.register(Commands.literal("tribes")
                .executes(c -> list(c.getSource()))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("top").executes(c -> top(c.getSource())))
                .then(Commands.literal("watch").executes(c -> watch(c.getSource())))
                .then(Commands.literal("roster").executes(c -> roster(c.getSource())))
                .then(Commands.literal("rules").executes(c -> rules(c.getSource())))
                .then(Commands.literal("needs").executes(c -> needs(c.getSource())))
                .then(Commands.literal("danger").executes(c -> danger(c.getSource())))
                .then(Commands.literal("log").executes(c -> log(c.getSource(), 15))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 50))
                                .executes(c -> log(c.getSource(), IntegerArgumentType.getInteger(c, "n")))))
                .then(Commands.literal("found").requires(s -> s.hasPermission(2)).executes(c -> found(c.getSource())))
                .then(Commands.literal("boost").requires(s -> s.hasPermission(2)).executes(c -> boost(c.getSource())))
                .then(Commands.literal("tp").requires(s -> s.hasPermission(2)).executes(c -> tp(c.getSource())))
                .then(Commands.literal("spawn").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("role", IntegerArgumentType.integer(0, 6))
                                .executes(c -> spawn(c.getSource(), IntegerArgumentType.getInteger(c, "role")))))
                .then(Commands.literal("stage").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 6))
                                .executes(c -> setStage(c.getSource(), IntegerArgumentType.getInteger(c, "n"))))));
    }

    private static String stageName(Tribe t) {
        return TribeConfig.STAGES[Math.max(0, Math.min(6, t.stage))];
    }

    private static Tribe nearest(CommandSourceStack src, TribeData data) {
        BlockPos me = BlockPos.containing(src.getPosition());
        Tribe best = null;
        double bd = Double.MAX_VALUE;
        for (Tribe t : data.tribes.values()) {
            double dx = t.center.getX() - me.getX(), dz = t.center.getZ() - me.getZ();
            double dist = dx * dx + dz * dz;
            if (dist < bd) { bd = dist; best = t; }
        }
        return best;
    }

    private static void say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }

    private static int list(CommandSourceStack src) {
        ServerLevel ow = src.getServer().overworld();
        TribeData data = TribeData.get(ow);
        if (data.tribes.isEmpty()) {
            say(src, "No tribes yet. Explore the world!");
            return 0;
        }
        BlockPos me = BlockPos.containing(src.getPosition());
        List<Tribe> l = new ArrayList<>(data.tribes.values());
        l.sort(Comparator.comparingDouble(t -> t.center.distSqr(me)));
        int n = 0;
        for (Tribe t : l) {
            if (n++ >= 15) break;
            StringBuilder en = new StringBuilder();
            for (int id : t.enemies) {
                Tribe o = data.tribes.get(id);
                if (o != null) en.append(o.name).append(' ');
            }
            Tribe p = t.parent == 0 ? null : data.tribes.get(t.parent);
            String line = String.format("%s [Lv%d] %s pop:%d @ %d %d %d%s  war: %s",
                    t.name, t.level, stageName(t), t.pop,
                    t.center.getX(), t.center.getY(), t.center.getZ(),
                    p == null ? "" : " (vassal of " + p.name + ")", en.length() == 0 ? "-" : en);
            say(src, line);
        }
        say(src, "Dragons slain by tribes: " + data.dragonsSlain + "   |   /tribes info, /tribes top");
        return l.size();
    }

    private static int info(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        Tribe p = t.parent == 0 ? null : data.tribes.get(t.parent);
        int vassals = 0;
        for (Tribe o : data.tribes.values()) if (o.parent == t.id) vassals++;
        StringBuilder en = new StringBuilder();
        for (int id : t.enemies) {
            Tribe o = data.tribes.get(id);
            if (o != null) en.append(o.name).append(' ');
        }
        say(src, "§e=== " + t.name + " === §7(" + Tribe.TRAITS[Math.floorMod(t.trait, 3)] + ")"
                + (p == null ? "" : " §6vassal of " + p.name));
        say(src, "Level " + t.level + "/" + t.maxLevel() + "   Stage: §b" + stageName(t));
        say(src, "Pop " + t.pop + "/" + t.popCap() + "   Food " + t.food + "   Wood " + t.wood + "   Wealth " + t.wealth);
        say(src, "Stone " + t.stone + "   Iron " + t.iron + "   Gold " + t.gold + "   Diamond " + t.diamond
                + "   Blaze " + t.blaze + "   Pearls " + t.pearls);
        say(src, "Mines started: " + TribeMine.minesStarted(t) + "/" + TribeMine.maxMines(t)
                + "   Tunnel cells dug: " + TribeMine.cellsDug(t));
        say(src, "Buildings planned: " + t.slots + "   finished: " + t.built
                + (t.bType >= 0 ? "   §aBuilding now: " + Blueprints.name(t.bType) + " (" + t.bProg + " blocks placed)" : ""));
        say(src, "Vassals/colonies: " + vassals + "   Strength: " + (int) t.strength() + "   Wars: " + (en.length() == 0 ? "-" : en));
        return 1;
    }

    private static int top(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Map<Integer, double[]> agg = new HashMap<>(); // root -> {strength, members}
        for (Tribe t : data.tribes.values()) {
            int r = TribeManager.rootId(data, t);
            double[] a = agg.computeIfAbsent(r, k -> new double[2]);
            a[0] += t.strength();
            a[1] += 1;
        }
        List<Map.Entry<Integer, double[]>> es = new ArrayList<>(agg.entrySet());
        es.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        say(src, "§6=== Mightiest empires ===");
        int n = 0;
        for (Map.Entry<Integer, double[]> e : es) {
            if (n >= 8) break;
            Tribe r = data.tribes.get(e.getKey());
            if (r == null) continue;
            n++;
            say(src, n + ". " + r.name + " - " + stageName(r) + " - power " + (int) e.getValue()[0]
                    + " - " + (int) e.getValue()[1] + " tribe(s)");
        }
        return es.size();
    }

    private static int boost(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        t.pop = Math.min(t.popCap(), t.pop + 20);
        t.food += 500;
        t.wood += 500;
        t.wealth += 1500;
        t.stone += 500;
        t.iron += 300;
        t.gold += 150;
        t.diamond += 100;
        t.blaze += 30;
        t.pearls += 30;
        data.setDirty();
        src.sendSuccess(() -> Component.literal("Boosted tribe " + t.name), true);
        return 1;
    }

    private static int setStage(CommandSourceStack src, int n) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        t.stage = n;
        t.cool = 0;
        t.level = Math.max(t.level, Math.min(t.maxLevel(), 2 * n));
        data.setDirty();
        src.sendSuccess(() -> Component.literal(t.name + " is now at stage: " + stageName(t)), true);
        return 1;
    }

    private static int tp(CommandSourceStack src) throws CommandSyntaxException {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        ServerPlayer p = src.getPlayerOrException();
        p.connection.teleport(t.center.getX() + 0.5, t.center.getY() + 1.0, t.center.getZ() + 0.5, p.getYRot(), p.getXRot());
        src.sendSuccess(() -> Component.literal("Teleported to " + t.name), true);
        return 1;
    }

    private static int spawn(CommandSourceStack src, int role) {
        ServerLevel lv = src.getLevel();
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        TribeManager.spawnMember(lv, t, role);
        t.pop++;
        data.setDirty();
        src.sendSuccess(() -> Component.literal("Spawned a " + TribesmanEntity.ROLE_NAMES[role] + " for " + t.name), true);
        return 1;
    }

    private static int found(CommandSourceStack src) throws CommandSyntaxException {
        ServerLevel lv = src.getLevel();
        if (lv.dimension() != Level.OVERWORLD) {
            src.sendFailure(Component.literal("Overworld only."));
            return 0;
        }
        BlockPos p = src.getPlayerOrException().blockPosition();
        int x = p.getX() + 14, z = p.getZ();
        BlockPos pos = new BlockPos(x, TribeManager.groundY(lv, x, z), z);
        if (!lv.hasChunksAt(pos.offset(-24, 0, -24), pos.offset(24, 0, 24))) {
            src.sendFailure(Component.literal("Chunks not loaded here."));
            return 0;
        }
        Tribe t = TribeManager.found(lv, TribeData.get(lv), pos);
        src.sendSuccess(() -> Component.literal("Founded tribe " + t.name), true);
        return 1;
    }

    // ---------- observation tools ----------
    /** Shows what the tribesman you are looking at is doing right now. */
    private static int watch(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        TribesmanEntity best = null;
        double bestDot = 0.96;
        for (TribesmanEntity e : src.getLevel().getEntitiesOfClass(TribesmanEntity.class, p.getBoundingBox().inflate(32.0))) {
            Vec3 to = e.getEyePosition().subtract(eye);
            if (to.length() < 0.5) continue;
            double dot = to.normalize().dot(look);
            if (dot > bestDot) { bestDot = dot; best = e; }
        }
        if (best == null) {
            src.sendFailure(Component.literal("Look straight at a tribesman (within 32 blocks) and try again."));
            return 0;
        }
        final TribesmanEntity m = best;
        Tribe t = m.tribe();
        say(src, "§e" + m.getName().getString());
        say(src, m.describe());
        if (t != null) {
            say(src, "Tribe " + t.name + ": food " + t.food + ", wood " + t.wood + ", stone " + t.stone
                    + (t.bType >= 0 ? ", site: " + Blueprints.name(t.bType) + " " + t.bProg + " blocks" : ", no construction site")
                    + (t.raidTarget != 0 ? ", ARMY MARCHING" : ""));
        }
        return 1;
    }

    /** What is everybody in the nearest tribe doing? */
    private static int roster(CommandSourceStack src) {
        ServerLevel ow = src.getServer().overworld();
        TribeData data = TribeData.get(ow);
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        TreeMap<String, Integer> roles = new TreeMap<>();
        TreeMap<String, Integer> doing = new TreeMap<>();
        int n = 0;
        for (TribesmanEntity m : TribeManager.members(ow, t)) {
            n++;
            roles.merge(TribesmanEntity.ROLE_NAMES[m.getRole()], 1, Integer::sum);
            doing.merge(TribeBrain.NAMES[m.task()] + (m.status().isEmpty() ? "" : " (" + m.status() + ")"), 1, Integer::sum);
        }
        say(src, "§e" + t.name + "§r: " + n + " loaded of " + t.pop + " people");
        say(src, "Roles: " + roles);
        say(src, "Doing: " + doing);
        return n;
    }

    /** Recent events: finished buildings, stuck NPCs, wars, expeditions... */
    private static int log(CommandSourceStack src, int n) {
        java.util.List<String> lines = TribeLog.recent(n);
        if (lines.isEmpty()) {
            say(src, "Nothing logged yet (the log is cleared when the server restarts).");
            return 0;
        }
        for (String l : lines) say(src, l);
        return lines.size();
    }

    /** The emergency rulebook the NPCs follow. */
    private static int rules(CommandSourceStack src) {
        say(src, "§e=== What tribesmen do in an emergency ===");
        say(src, "§fON FIRE / IN LAVA: run to the nearest water (or away from the flames).");
        say(src, "§fHURT (below 60% HP): workers stop and recover at the campfire; they eat from the tribe food when no enemy is within 7 blocks.");
        say(src, "§fVERY HURT with an enemy near (workers below 45%, fighters below 25%): flee home to the warriors.");
        say(src, "§fALARM (someone is attacked near the village): workers take cover at the campfire, warriors and archers rush to the spot.");
        say(src, "§fCREEPERS: everybody keeps 8 blocks away. Only warriors and archers hunt monsters; others fight what is within 6 blocks.");
        say(src, "§fDANGEROUS PLACES: where someone got hurt or died, workers avoid that 16x16 area for a while (/tribes danger).");
        say(src, "§fMORE DEATHS: if monsters keep killing people, the tribe trains more warriors and archers.");
        say(src, "§fPORTALS: nobody walks into a Nether portal except warriors on a Nether expedition.");
        say(src, "§fSTUCK / IN A WALL / TOO FAR: they get freed and sent home (/tribes log).");
        say(src, "§e=== How they decide what to do ===");
        say(src, "§fThe tribe works out its needs (famine, missing wood or stone, a building site, danger). Everybody picks the task that fits those needs and their profession, and gets bored of repeating one thing (/tribes needs).");
        say(src, "§fTools: nobody carries tools all day. Before a task they walk to the storehouse and take the tool; loot is carried there and stored; hurt people fetch food rations from it.");
        say(src, "§fWAR only for a reason: revenge for killed people, famine, a border dispute, or conquest by a mighty tribe. Allies and vassals join in.");
        say(src, "§fAFTER A VICTORY: prisoners go to the castle jail and are put on trial (execution, forced labour, ransom or release); a much weaker tribe is annexed into the empire.");
        return 1;
    }

    /** What the nearest tribe has learned to fear. */
    private static int danger(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        say(src, "§e" + t.name + "§r threat level: " + t.threat + " (more fighters are trained when this is high)");
        if (t.danger.isEmpty()) {
            say(src, "No dangerous areas remembered.");
            return 0;
        }
        List<Map.Entry<Long, Integer>> es = new ArrayList<>(t.danger.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int n = 0;
        for (Map.Entry<Long, Integer> e : es) {
            if (n++ >= 8) break;
            int x = (int) (e.getKey() >> 32) * 16;
            int z = (int) (long) e.getKey() * 16;
            say(src, "Area around " + x + ", " + z + ": fear " + e.getValue() + (e.getValue() >= 4 ? " (avoided)" : ""));
        }
        return es.size();
    }

    /** The needs the nearest tribe has right now - this is what drives everyone's choices. */
    private static int needs(CommandSourceStack src) {
        TribeData data = TribeData.get(src.getServer().overworld());
        Tribe t = nearest(src, data);
        if (t == null) { src.sendFailure(Component.literal("No tribes yet.")); return 0; }
        say(src, "§e" + t.name + "§r needs (0 = nothing, 3 = crisis). Only updated while a player is near.");
        for (int k = 0; k < TribeBrain.N; k++) {
            if (t.needWhy[k] == null || t.needWhy[k].isEmpty()) continue;
            say(src, String.format("%-20s %.1f  - %s", TribeBrain.NAMES[k], t.needs[k], t.needWhy[k]));
        }
        say(src, "Storehouse: " + (t.hasStore ? "built" : "not yet") + "   Castle: " + (t.castleDone ? "finished" : t.castle ? "under construction" : "not yet")
                + "   Jail: " + (t.hasJail ? "yes" : "no") + "   Large farms: " + t.bigFarms);
        return 1;
    }
}
