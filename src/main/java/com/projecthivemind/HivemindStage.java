package com.projecthivemind;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/** Where a player is in the Project Hivemind progression for the current world. */
public enum HivemindStage implements StringRepresentable {
    /** Has not picked Steve or Hivemind yet. */
    UNCHOSEN("unchosen"),
    /** Plays vanilla Minecraft. */
    STEVE("steve"),
    /** Hivemind, still a small larva walking around looking for a spot for the Hive Heart. */
    LARVA("larva"),
    /** Hivemind with a Hive Heart placed: bodyless, RTS gameplay. */
    HIVE("hive");

    public static final Codec<HivemindStage> CODEC = StringRepresentable.fromEnum(HivemindStage::values);

    private final String name;

    HivemindStage(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
