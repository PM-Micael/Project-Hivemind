# Project Hivemind: hive levels

Everything a hive gets at each level, and what the player has to do to reach the next one. The numbers come from `HiveLevels.java`
(and `HiveHeart.java` for the Heart's body). There are 6 levels. Level 1 is reached by planting the Hive Heart.

## At a glance

| Level | Heart health | Storage slots | Hive area (border) | Heart body (wide x tall) | Teams | Portals |
|---|---|---|---|---|---|---|
| 1 | 10 hearts (20 HP) | 27 | 11 x 11 (radius 5) | 1 x 1 | 1 | 0 |
| 2 | 20 hearts (40 HP) | 54 | 29 x 29 (radius 14) | 1 x 2 | 2 | 1 |
| 3 | 30 hearts (60 HP) | 81 | 47 x 47 (radius 23) | 3 x 3 | 2 | 1 |
| 4 | 40 hearts (80 HP) | 108 | 65 x 65 (radius 32) | 5 x 5 | 2 | 1 |
| 5 | 50 hearts (100 HP) | 135 | 83 x 83 (radius 41) | 7 x 7 | 2 | 1 |
| 6 | 60 hearts (120 HP) | 162 | 101 x 101 (radius 50) | 9 x 9 | 2 | 1 |

## Units the hive may have at once

| Level | Scouts | Workers | Soldiers | Collectors | Feeders | Total |
|---|---|---|---|---|---|---|
| 1 | 1 | 1 | 1 | 1 | 1 | 5 |
| 2 | 1 | 2 | 3 | 1 | 2 | 9 |
| 3 | 2 | 3 | 5 | 2 | 3 | 15 |
| 4 | 2 | 5 | 7 | 3 | 4 | 21 |
| 5 | 2 | 7 | 10 | 3 | 5 | 27 |
| 6 | 2 | 7 | 15 | 3 | 6 | 33 |

The Heart makes the missing units itself, one of each kind below its cap every 10 seconds, so new units appear by themselves after a
level-up. (The furnace, brewing stand and the other workstations are not level rewards: they come from the Research tab.)

## Sight

| Level | The Heart sees | Scouts see | Every other unit sees |
|---|---|---|---|
| 1 | 16 blocks | 32 blocks | 32 blocks |
| 2 | 32 | 64 | 32 |
| 3 | 48 | 96 | 32 |
| 4 | 64 | 128 | 32 |
| 5 | 80 | 160 | 32 |
| 6 | 96 | 192 | 32 |

## What each level adds, and what it takes to get to the next

### Level 1: planting the Heart
- Starts with 10 hearts of health, 27 storage slots, an 11 x 11 hive area, and one unit of every kind.
- The Research tab is there from the start.
- **To reach level 2:** explore 5 chunks with the hive's units, **and** research the Crafting Table.

### Level 2
- Heart health doubles to 20 hearts; storage grows to 54 slots; the hive area is 29 x 29.
- Caps: workers 2, soldiers 3, feeders 2 (scouts and collectors stay at 1).
- The Portals tab appears: scouts can place one hive portal, and a second team (one for the portal) exists.
- The Heart's body is 1 x 2.
- **To reach level 3:** the hive's units kill 10 mobs, **and** the hive has been alive for one whole in-game day (24000 ticks, 20 minutes).

### Level 3
- 30 hearts; storage 81 slots (scrolling); hive area 47 x 47.
- Caps: scouts 2, workers 3, soldiers 5, collectors 2, feeders 3.
- Heart body 3 x 3.
- **To reach level 4:** a unit gets down to Y = 0, **and** the hive collects 10 coal, **and** 9 iron ingots.

### Level 4
- 40 hearts; storage 108 slots; hive area 65 x 65.
- Caps: workers 5, soldiers 7, collectors 3, feeders 4.
- Heart body 5 x 5.
- **To reach level 5:** enter the Nether (a unit or the camera), **and** collect 3 blaze rods.

### Level 5
- 50 hearts; storage 135 slots; hive area 83 x 83.
- Caps: workers 7, soldiers 10, feeders 5.
- Heart body 7 x 7.
- **To reach level 6:** defeat the Ender Dragon (while the hive is in the End). Nothing else is asked.

### Level 6 (the highest)
- 60 hearts; storage 162 slots; hive area 101 x 101 (the biggest it gets).
- Caps: soldiers 15, feeders 6.
- Heart body 9 x 9.
- No further quest.

## What happens at every level-up
- The Heart is fully healed at its new maximum health.
- The storage becomes a new, bigger container (nothing in it is lost); an open hive menu is closed.
- The hive area and the Heart's sight grow, and the light blocks of the Glowstone reward are worked out again over the bigger area.
- The creep (see below) can spread out to the new border.
- A sound plays and the player is told the new level.

## Things that are not tied to the level
- Evolution (Research tab) tasks are done by consuming items and are independent of the level: workstations, storage stack size,
  absorption (copper), enchanting, the Crafter, Cake and so on. Only the level-2 quest asks for the Crafting Table.
- The hive's crafting grid is 2 x 2 until the Crafting Table has been researched, whatever the level.
- Creep: the Heart turns the blocks 1 to 4 below its own level into creep blocks, spreading outward from the Heart at about 1 block
  every 30 seconds, never past the hive border (so the most it can ever cover is the border of the current level).

## Operator commands
```
/hivemind level get
/hivemind level set <1-6>
/hivemind level up
/hivemind level down
/hivemind creep get
/hivemind creep radius <blocks>
```
