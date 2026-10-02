package com.projecthivemind;

/** The choices in the context menu for a mob, as sent from the client to the server. */
public enum MobAction {
    ATTACK,
    /** Scouts walk to a villager and open its trades. */
    TRADE,
    /** Soldiers become the unit's bodyguard: they stay by it and fight what threatens it, until it dies or the job is cancelled. */
    GUARD,
    /** Stop every unit that is attacking that mob. */
    CANCEL
}
