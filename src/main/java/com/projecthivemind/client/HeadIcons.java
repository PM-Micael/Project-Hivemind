package com.projecthivemind.client;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.projecthivemind.ModEntities;
import com.projecthivemind.UnitKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * The head of a unit's mob model, drawn small, for the buttons of the hive menu. It is the real model, rendered the way
 * the inventory screen draws the player, with the view moved up so that the head fills the button and the rest is cut
 * off. A kind of unit with no live example to show gets a stand-in made here, on the client only.
 */
final class HeadIcons {
    private static final Map<UnitKind, LivingEntity> STAND_INS = new EnumMap<>(UnitKind.class);

    private HeadIcons() {
    }

    /** A stand-in of this kind, for when there is no real unit to draw. */
    @Nullable
    static LivingEntity standIn(UnitKind kind) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        LivingEntity entity = STAND_INS.get(kind);
        if (entity == null || entity.level() != level) {
            entity = switch (kind) {
                case SCOUT -> ModEntities.HIVE_SCOUT.get().create(level);
                case SOLDIER -> ModEntities.HIVE_SOLDIER.get().create(level);
                case WORKER -> ModEntities.HIVE_WORKER.get().create(level);
                case COLLECTOR -> ModEntities.HIVE_COLLECTOR.get().create(level);
            };
            if (entity != null) {
                STAND_INS.put(kind, entity);
            }
        }
        return entity;
    }

    /** How many pixels a block is, drawn: small mobs need blowing up for their head to fill a button. */
    private static int scale(UnitKind kind) {
        return kind == UnitKind.COLLECTOR ? 110 : 44;
    }

    /** Draw the head of this entity into the box, cut off at its edges. */
    static void draw(GuiGraphics graphics, int x1, int y1, int x2, int y2, @Nullable LivingEntity entity, UnitKind kind) {
        if (entity == null) {
            return;
        }
        // The view is centred on the entity's middle by default; moving it down by the difference puts the eyes there.
        float yOffset = entity.getEyeHeight() - entity.getBbHeight() / 2.0F;
        InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x1, y1, x2, y2, scale(kind), yOffset, 0.0F, 0.0F, entity);
    }
}
