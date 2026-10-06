package com.projecthivemind;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The switch and the counters of the rule that keeps hostile mobs from spawning inside a hive border (see {@link CommonEvents}). The counters
 * are how many hostile spawn checks were refused for being inside a border and how many were left alone, since the game started: if the rule
 * ever seems to stop spawning altogether, they and the switch ({@code /hivemind spawnguard}) show whether it is the cause.
 */
public final class HiveSpawnGuard {
    public static volatile boolean enabled = true;
    public static final AtomicLong blocked = new AtomicLong();
    public static final AtomicLong allowed = new AtomicLong();

    private HiveSpawnGuard() {
    }
}
