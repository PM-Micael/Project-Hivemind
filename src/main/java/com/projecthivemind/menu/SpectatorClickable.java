package com.projecthivemind.menu;

/**
 * Marks a menu whose clicks arrive over the mod's own click packet. The bodyless hivemind is in spectator mode, and the
 * server ignores vanilla container clicks from spectators, so every menu the hivemind uses needs this.
 */
public interface SpectatorClickable {
}
