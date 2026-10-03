package com.projecthivemind;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

public enum UnitKind implements StringRepresentable {
    /** Looks like a husk. Selectable and able to walk; its real job is still to come. */
    SCOUT("scout"),
    WORKER("worker"),
    SOLDIER("soldier"),
    /** Autonomous: fetches dropped items and delivers them to the Hive Heart. Cannot be commanded. */
    COLLECTOR("collector"),
    /** Autonomous, like the collector, and flies like a bee: channels on crops and saplings and feeds composters. Cannot be commanded. */
    FEEDER("feeder");

    public static final Codec<UnitKind> CODEC = StringRepresentable.fromEnum(UnitKind::values);

    private final String name;

    UnitKind(String name) {
        this.name = name;
    }

    /** True for the units that cannot be commanded or put in a team: they are selected alone, and work by themselves. */
    public boolean passive() {
        return this == COLLECTOR || this == FEEDER;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
