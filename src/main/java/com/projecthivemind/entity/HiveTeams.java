package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * The teams of a hive: groups of its units that act together. A hive has a team for the Heart and one for each portal it may have; this works on a list of
 * them, so more can be added later. A unit is in at most one team. What being in a team means is up to the units: see
 * {@link TeamFollowGoal} for the first rule, that workers and soldiers stay close to the team's scout.
 */
public final class HiveTeams {
    /** The most teams a hive can have; how many it has now depends on its level (see HiveLevel#teamCount). */
    public static final int MAX_TEAMS = 8;

    private final List<Set<UUID>> teams = new ArrayList<>();
    /** How close each team keeps to its scout, in blocks: the area around the scout. */
    private final int[] radius = new int[MAX_TEAMS];
    public static final int MIN_RADIUS = 3;
    public static final int MAX_RADIUS = 24;
    public static final int DEFAULT_RADIUS = 8;

    public HiveTeams() {
        for (int i = 0; i < MAX_TEAMS; i++) {
            teams.add(new LinkedHashSet<>());
            radius[i] = DEFAULT_RADIUS;
        }
    }

    /** Which team the unit is in, or -1. */
    public int teamOf(UUID unit) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).contains(unit)) {
                return i;
            }
        }
        return -1;
    }

    public boolean isMember(UUID unit) {
        return teamOf(unit) >= 0;
    }

    public Set<UUID> members(int team) {
        return team >= 0 && team < teams.size() ? Set.copyOf(teams.get(team)) : Set.of();
    }

    /** Put the unit in a team (taking it out of any other first). */
    public void join(UUID unit, int team) {
        leave(unit);
        if (team >= 0 && team < teams.size()) {
            teams.get(team).add(unit);
        }
    }

    public void leave(UUID unit) {
        teams.forEach(members -> members.remove(unit));
    }

    public int radius(int team) {
        return team >= 0 && team < radius.length ? radius[team] : DEFAULT_RADIUS;
    }

    public void setRadius(int team, int blocks) {
        if (team >= 0 && team < radius.length) {
            radius[team] = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, blocks));
        }
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Set<UUID> members : teams) {
            ListTag saved = new ListTag();
            members.forEach(id -> saved.add(NbtUtils.createUUID(id)));
            CompoundTag tag = new CompoundTag();
            tag.put("Members", saved);
            tag.putInt("Radius", radius[list.size()]);
            list.add(tag);
        }
        return list;
    }

    public void load(ListTag list) {
        teams.forEach(Set::clear);
        for (int i = 0; i < list.size() && i < teams.size(); i++) {
            ListTag saved = list.getCompound(i).getList("Members", Tag.TAG_INT_ARRAY);
            radius[i] = list.getCompound(i).contains("Radius") ? Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, list.getCompound(i).getInt("Radius"))) : DEFAULT_RADIUS;
            for (Tag member : saved) {
                teams.get(i).add(NbtUtils.loadUUID(member));
            }
        }
    }
}
