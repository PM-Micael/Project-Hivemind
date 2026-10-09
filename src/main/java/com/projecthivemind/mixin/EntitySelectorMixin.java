package com.projecthivemind.mixin;

import java.util.function.Predicate;

import com.projecthivemind.ScoutControl;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;

/**
 * A player who is controlling a scout counts as a player where the scout is. The hivemind's own entity is always a spectator, which is
 * what the game and other mods leave out when they look for players who are near (a spawner waking up, a boss noticing someone, a trap
 * going off), and while a scout is controlled that entity is kept right at the scout.
 *
 * <p>Those checks nearly all go through the game's two shared player filters, "not a spectator" and "not creative or a spectator", so this
 * lets the controlling player through both. Nothing here knows of any other mod: whatever uses the game's filters is covered, and what
 * checks {@code isSpectator()} for itself is not.
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Shadow
    @Final
    @Mutable
    public static Predicate<Entity> NO_SPECTATORS;

    @Shadow
    @Final
    @Mutable
    public static Predicate<Entity> NO_CREATIVE_OR_SPECTATOR;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void projecthivemind$countControllingPlayer(CallbackInfo ci) {
        Predicate<Entity> noSpectators = NO_SPECTATORS;
        NO_SPECTATORS = entity -> noSpectators.test(entity) || projecthivemind$controlling(entity);
        Predicate<Entity> noCreativeOrSpectator = NO_CREATIVE_OR_SPECTATOR;
        NO_CREATIVE_OR_SPECTATOR = entity -> noCreativeOrSpectator.test(entity) || projecthivemind$controlling(entity);
    }

    /** A server player in the middle of controlling a scout (the client never knows, and has nothing to ask about). */
    private static boolean projecthivemind$controlling(Entity entity) {
        return entity instanceof ServerPlayer player && ScoutControl.isControlling(player);
    }
}
