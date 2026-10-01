package com.projecthivemind;

/** The choices in the context menu for a mob, as sent from the client to the server. */
public enum MobAction {
    ATTACK,
    /** Stop every unit that is attacking that mob. */
    CANCEL
}
