package com.tribesmod;

import net.minecraft.util.RandomSource;

/**
 * Needs-based decision making. The tribe constantly works out what it needs (food, wood, stone,
 * defence, a building to finish...). Every tribesman then picks the task that fits those needs best,
 * weighted by his profession, and gets bored of doing the same thing for too long.
 */
public final class TribeBrain {
    private TribeBrain() {}

    public static final int FARM = 0, ANIMALS = 1, FISH = 2, WOOD = 3, MINE = 4, BUILD = 5, TRADE = 6, GUARD = 7, RAID = 8;
    public static final int N = 9;
    public static final String[] NAMES = {"farming", "tending animals", "fishing", "cutting wood", "mining",
            "building", "trading", "guarding", "fighting in the war"};
    /** The task a profession does when nothing else is needed (also used when NEEDS_AI is off). */
    public static final int[] PRIMARY = {FARM, WOOD, TRADE, GUARD, MINE, BUILD, GUARD};

    /** How much each profession likes each task (0 = never). Rows: farmer, woodcutter, trader, warrior, miner, builder, archer. */
    static final double[][] AFF = {
            {1.00, 0.70, 0.60, 0.30, 0.10, 0.25, 0.10, 0.00, 0.00},
            {0.30, 0.00, 0.20, 1.00, 0.20, 0.40, 0.00, 0.00, 0.00},
            {0.20, 0.00, 0.20, 0.10, 0.00, 0.10, 1.00, 0.00, 0.00},
            {0.15, 0.00, 0.10, 0.25, 0.15, 0.30, 0.00, 1.00, 1.00},
            {0.10, 0.00, 0.10, 0.25, 1.00, 0.35, 0.00, 0.00, 0.00},
            {0.15, 0.00, 0.10, 0.40, 0.30, 1.00, 0.00, 0.00, 0.00},
            {0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 1.00, 1.00}};

    /** Works out what the tribe needs right now (0 = nothing, 3 = crisis) and why. */
    public static void assess(Tribe t, long now) {
        double[] n = t.needs;
        String[] w = t.needWhy;
        boolean job = t.bType >= 0;
        String site = job ? Blueprints.name(t.bType) : "";

        int target = 60 + t.pop * 8;
        double famine = t.food < t.pop * 3 ? 3.0 : t.food < target * 0.5 ? 1.8 : t.food < target ? 1.0 : 0.3;
        n[FARM] = famine;
        w[FARM] = famine >= 3 ? "famine - only " + t.food + " food left" : famine >= 1.8 ? "food is running low (" + t.food + ")"
                : famine >= 1.0 ? "filling up the granary" : "routine work in the fields";
        n[ANIMALS] = 0.2 + famine * 0.6;
        w[ANIMALS] = famine >= 1.8 ? "we need meat" : "keeping the herds healthy";
        n[FISH] = famine * 0.5;
        w[FISH] = "fresh fish for the people";

        boolean woodShort = job && t.wood < 20;
        n[WOOD] = woodShort ? 2.8 : t.wood < 60 ? 1.5 : 0.4;
        w[WOOD] = woodShort ? "the " + site + " needs wood" : t.wood < 60 ? "wood stock is low (" + t.wood + ")" : "wood for later";

        boolean stoneShort = job && t.stone < 30;
        n[MINE] = stoneShort ? 2.8 : t.stone < 80 ? 1.5 : t.iron < 40 ? 1.3 : (t.diamond < 20 && t.stage >= 1) ? 1.0 : 0.4;
        w[MINE] = stoneShort ? "the " + site + " needs stone" : t.stone < 80 ? "stone is running out (" + t.stone + ")"
                : t.iron < 40 ? "we need iron for better tools" : "searching for diamonds";

        n[BUILD] = job ? 2.6 : 0.1;
        w[BUILD] = job ? "the " + site + " is under construction (" + t.bProg + " blocks placed)" : "nothing to build right now";

        n[TRADE] = 0.5 + (t.wealth < 100 ? 0.5 : 0.0);
        w[TRADE] = "the tribe needs wealth";

        boolean alarm = t.alarmUntil > now;
        boolean war = !t.enemies.isEmpty();
        n[GUARD] = alarm ? 3.0 : war ? 2.2 : t.threat > 8 ? 1.5 : 0.8;
        w[GUARD] = alarm ? "the village is under attack" : war ? "we are at war" : t.threat > 8 ? "monsters keep killing us" : "keeping the village safe";

        n[RAID] = t.raidTarget != 0 ? 3.0 : 0.0;
        w[RAID] = "the army is marching";
    }

    /** Picks the best task for this tribesman right now. */
    public static int choose(TribesmanEntity m, Tribe t, RandomSource r) {
        int role = m.getRole();
        int best = PRIMARY[role];
        double bs = -1.0;
        for (int k = 0; k < N; k++) {
            double aff = AFF[role][k];
            if (k == RAID && !m.isFighter() && t.raidTarget != 0 && m.isMilitia()) aff = 0.9; // citizens join big wars
            if (aff <= 0.0) continue;
            if (k == RAID && t.raidTarget == 0) continue;
            if (k == ANIMALS && t.penCount == 0) continue;
            if (m.recentlyFailed(k)) continue;
            double s = aff * (0.4 + t.needs[k]) * (0.85 + 0.3 * r.nextDouble()) / (1.0 + m.fatigue(k) * 0.1);
            if (s > bs) { bs = s; best = k; }
        }
        return best;
    }
}
