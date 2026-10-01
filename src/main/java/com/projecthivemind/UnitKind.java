package com.projecthivemind;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

public enum UnitKind implements StringRepresentable {
    WORKER("worker"),
    SOLDIER("soldier"),
    /** Autonomous: fetches dropped items and delivers them to the Hive Heart. Cannot be commanded. */
    COLLECTOR("collector");

    public static final Codec<UnitKind> CODEC = StringRepresentable.fromEnum(UnitKind::values);

    private final String name;

    UnitKind(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
