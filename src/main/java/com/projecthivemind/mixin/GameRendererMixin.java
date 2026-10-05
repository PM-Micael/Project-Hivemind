package com.projecthivemind.mixin;

import com.projecthivemind.client.ClientControl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.GameType;

/**
 * The game draws the player's hands in first person except for spectators, and the hivemind's own entity always is one. While the player
 * controls a scout the hands are drawn after all: they hold what the scout holds (see ControlHands).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType projecthivemind$handsWhileControlling(MultiPlayerGameMode gameMode) {
        return ClientControl.active() ? GameType.SURVIVAL : gameMode.getPlayerMode();
    }
}
