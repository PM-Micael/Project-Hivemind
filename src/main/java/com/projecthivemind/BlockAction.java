package com.projecthivemind;

/** The choices in the context menu for a block, as sent from the client to the server. */
public enum BlockAction {
    WALK_TO,
    DIG,
    INTERACT,
    /** Stop every unit that is doing something to that block. */
    CANCEL,
    /** Selected workers keep mining that block: whenever there is a block there, they dig it (for a cobblestone generator). */
    REPEAT_DIG
}
