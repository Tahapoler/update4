package com.tribesmod;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Short in-memory history of what the NPCs did (for /tribes log). Not saved to disk. */
public final class TribeLog {
    private TribeLog() {}

    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final int MAX = 300;

    public static synchronized void add(ServerLevel level, String who, String msg) {
        long sec = level.getGameTime() / 20;
        LOG.addLast(String.format("[%02d:%02d] %s: %s", (sec / 60) % 100, sec % 60, who, msg));
        while (LOG.size() > MAX) LOG.pollFirst();
    }

    public static synchronized List<String> recent(int n) {
        List<String> all = new ArrayList<>(LOG);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}
