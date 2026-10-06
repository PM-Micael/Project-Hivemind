package com.projecthivemind.block;

/** What a hive relay does: three give a signal while something is true of the hive, one takes a signal and tells the Heart to do something. */
public enum RelayChannel {
    /** On while a hostile mob is inside the hive border. */
    HOSTILE("hostile", true),
    /** On while any mob (not the hive's own) is inside the hive border. */
    ANY("any", true),
    /** On while the Heart's health is under the percentage set on the Redstone tab. */
    HEALTH("health", true),
    /** A signal on it calls every soldier that is outside the border back to the Heart. */
    RECALL("recall", false);

    private final String id;
    private final boolean output;

    RelayChannel(String id, boolean output) {
        this.id = id;
        this.output = output;
    }

    public String id() {
        return id;
    }

    /** True if the relay gives a signal; false if it takes one. */
    public boolean output() {
        return output;
    }
}
