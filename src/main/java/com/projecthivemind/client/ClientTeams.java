package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.entity.HiveTeams;

/** How far around its scout each team keeps together, and how far its soldiers go after hostile mobs, as last told by the server. */
public final class ClientTeams {
    private static List<Integer> radii = List.of();
    private static List<Integer> attackRadii = List.of();

    private ClientTeams() {
    }

    public static void update(List<Integer> newRadii, List<Integer> newAttackRadii) {
        radii = List.copyOf(newRadii);
        attackRadii = List.copyOf(newAttackRadii);
    }

    /** How many teams the hive has right now. */
    public static int count() {
        return Math.max(1, radii.size());
    }

    public static int radius(int team) {
        return team >= 0 && team < radii.size() ? radii.get(team) : HiveTeams.DEFAULT_RADIUS;
    }

    /** How far from its scout a soldier of the team goes after hostile mobs (the yellow area). */
    public static int attackRadius(int team) {
        return team >= 0 && team < attackRadii.size() ? attackRadii.get(team) : HiveTeams.DEFAULT_ATTACK_RADIUS;
    }

    public static void reset() {
        radii = List.of();
        attackRadii = List.of();
    }
}
