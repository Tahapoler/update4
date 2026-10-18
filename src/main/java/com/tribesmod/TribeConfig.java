package com.tribesmod;

/** All the knobs in one place. Change a value, push to GitHub, download the new jar. */
public final class TribeConfig {
    private TribeConfig() {}

    /** Global growth speed. 1.0 = normal, 2.0 = twice as fast, 0.5 = half. */
    public static final double GROWTH = 1.0;
    /** Maximum number of tribes (colonies and vassals included). */
    public static final int MAX_TRIBES = 100;
    /** Minimum distance between naturally founded tribes. */
    public static final int MIN_DIST = 150;
    /** Maximum number of physical tribesmen per tribe (the rest of the population is simulated). */
    public static final int MAX_ENTITIES = 60;
    /** When a tribe slays the dragon for the first time, really remove the Ender Dragon from the End. */
    public static final boolean REAL_DRAGON_KILL = true;
    /** Tribes build a real Nether portal once they reach the Nether stage. */
    public static final boolean BUILD_PORTAL = true;

    // ---------- player-like AI (v3) ----------
    /** Builders place new buildings block by block (walking, swinging, holding the block). false = instant like v2. */
    public static final boolean REALISTIC_BUILDING = true;
    /** If a construction site has no builder for ~3 minutes, finish it instantly so the village never stalls. */
    public static final boolean INSTANT_FALLBACK = true;
    /** Tribesmen walk to loose items on the ground and pick them up. */
    public static final boolean PICK_UP_ITEMS = true;
    /** Workers go home and rest around the campfire at night (warriors keep guard). */
    public static final boolean NIGHT_REST = true;
    /** Players can trade with tribesmen by right-clicking with an emerald. */
    public static final boolean PLAYER_TRADE = true;

    // ---------- v3.1: autonomous NPC world ----------
    // ---------- v3.3: needs, storage, empire ----------
    /** Tribesmen choose what to do from the tribe's real needs (with variety), not just their profession. */
    public static final boolean NEEDS_AI = true;
    /** Tools and food are taken from the storehouse, loot is carried there; hands stay empty otherwise. */
    public static final boolean STORAGE_TRIPS = true;
    /** Tribes of level 5+ build a huge castle (keep, towers, walls, jail, treasury). */
    public static final boolean CASTLES = true;
    /** Tribes of level 3+ build large irrigated farms outside the village. */
    public static final boolean BIG_FARMS = true;
    /** Winners jail enemy people; the jail holds a trial (execute / forced labour / ransom / release). */
    public static final boolean PRISONERS = true;
    /** Survival instincts: flee when hurt, run from fire, alarm in the village, remember dangerous places. */
    public static final boolean INSTINCTS = true;
    /** Show what each NPC is doing above its head (e.g. "Chopping tree"). */
    public static final boolean SHOW_STATUS = true;
    /** NPCs that get stuck (no movement, inside walls, far from home) are freed or teleported home. */
    public static final boolean STUCK_FIX = true;
    /** Tribes of level 4+ build a cobblestone wall with 4 gates around the village. */
    public static final boolean WALLS = true;
    /** Archers: shoot arrows, stand guard on the watchtowers. */
    public static final boolean ARCHERS = true;
    /** Farmers breed and slaughter animals in pens, and go fishing. */
    public static final boolean ANIMALS = true;
    /** Warriors really walk through the tribe's Nether portal on expeditions (stage 3+). */
    public static final boolean PHYSICAL_NETHER = true;
    /** Tribes at war send their whole army to the enemy village when both are loaded. */
    public static final boolean PHYSICAL_RAIDS = true;

    public static final String[] STAGES = {
            "Stone Age",
            "Iron Age",
            "Diamond Age",
            "Nether Campaign",
            "Stronghold Quest",
            "End Siege",
            "EMPIRE (Dragon Slayers)"
    };
}
