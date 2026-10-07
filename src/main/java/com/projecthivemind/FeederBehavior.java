package com.projecthivemind;

/**
 * What one feeder does with its time. A feeder never has orders: it waits in the Heart and does what its settings say. Each
 * feeder has its own settings, edited from its page of the hive menu, and none are on to begin with.
 *
 * @param channelCrops    fly to crops that are not fully grown and channel on them, making them grow 300 times as fast. A crop that
 *                        gets fully grown while it is channelled on is broken, so that what it grew drops on the ground
 * @param useBoneMeal     while channelling, also use bone meal from the hive on the crop; only counts with channelCrops or channelSaplings
 * @param channelSaplings the same as channelCrops for saplings (50 times as fast); a sapling that grows is a tree, and is left standing
 * @param useComposter    feed the composters inside the hive border with the item the feeder was given, and take out the bone meal they make
 * @param gatherPollen    once the hive has consumed a bee nest: fly to flowers, gather pollen and bring it back to the Heart as honey (on unless turned off)
 */
public record FeederBehavior(boolean channelCrops, boolean useBoneMeal, boolean channelSaplings, boolean useComposter, boolean gatherPollen) {
    /** Feeders do nothing until the player turns something on. */
    public static final FeederBehavior DEFAULT = new FeederBehavior(false, false, false, false, true);

    private static final int CHANNEL_CROPS = 1;
    private static final int USE_BONE_MEAL = 2;
    private static final int CHANNEL_SAPLINGS = 4;
    private static final int USE_COMPOSTER = 8;
    /** Saved when the option is OFF, so that a feeder saved before the option existed has it on. */
    private static final int NO_POLLEN = 16;

    /** The checkboxes packed into one number. */
    public int flags() {
        return (channelCrops ? CHANNEL_CROPS : 0) | (useBoneMeal ? USE_BONE_MEAL : 0) | (channelSaplings ? CHANNEL_SAPLINGS : 0)
                | (useComposter ? USE_COMPOSTER : 0) | (gatherPollen ? 0 : NO_POLLEN);
    }

    /** Feeders have no radii; the menu still sends and shows four, all zero. */
    public int[] radii() {
        return new int[4];
    }

    public static FeederBehavior from(int flags, int[] radii) {
        return new FeederBehavior((flags & CHANNEL_CROPS) != 0, (flags & USE_BONE_MEAL) != 0, (flags & CHANNEL_SAPLINGS) != 0,
                (flags & USE_COMPOSTER) != 0, (flags & NO_POLLEN) == 0);
    }
}
