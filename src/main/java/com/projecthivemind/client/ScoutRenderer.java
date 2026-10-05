package com.projecthivemind.client;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.projecthivemind.entity.HiveScout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a scout as a player: the player model, with the owner's skin (the one a default player of that account would have when the owner is
 * not in the game), and the player's own poses and animations, so a crouching or swimming scout looks as a crouching or swimming player does.
 */
public class ScoutRenderer extends HumanoidMobRenderer<HiveScout, PlayerModel<HiveScout>> {
    private final PlayerModel<HiveScout> wideModel;
    private final PlayerModel<HiveScout> slimModel;

    public ScoutRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        // The armor models are the same for both arm widths in the standing pose.
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    private static PlayerSkin skinOf(HiveScout scout) {
        UUID owner = scout.ownerId();
        PlayerInfo info = owner == null || Minecraft.getInstance().getConnection() == null
                ? null : Minecraft.getInstance().getConnection().getPlayerInfo(owner);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner != null ? owner : scout.getUUID());
    }

    @Override
    public ResourceLocation getTextureLocation(HiveScout scout) {
        return skinOf(scout).texture();
    }

    @Override
    public void render(HiveScout scout, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        PlayerModel<HiveScout> playerModel = skinOf(scout).model() == PlayerSkin.Model.SLIM ? slimModel : wideModel;
        this.model = playerModel;
        setModelProperties(scout, playerModel);
        super.render(scout, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /** What the arm does with what is in that hand: the player renderer's own choice (bow drawn, crossbow loading or held, shield up). */
    private static HumanoidModel.ArmPose armPose(HiveScout scout, InteractionHand hand) {
        ItemStack stack = scout.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (scout.getUsedItemHand() == hand && scout.getUseItemRemainingTicks() > 0) {
            switch (stack.getUseAnimation()) {
                case BLOCK:
                    return HumanoidModel.ArmPose.BLOCK;
                case BOW:
                    return HumanoidModel.ArmPose.BOW_AND_ARROW;
                case SPEAR:
                    return HumanoidModel.ArmPose.THROW_SPEAR;
                case CROSSBOW:
                    return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                case SPYGLASS:
                    return HumanoidModel.ArmPose.SPYGLASS;
                case TOOT_HORN:
                    return HumanoidModel.ArmPose.TOOT_HORN;
                case BRUSH:
                    return HumanoidModel.ArmPose.BRUSH;
                default:
                    break;
            }
        } else if (!scout.swinging && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    private static void setModelProperties(HiveScout scout, PlayerModel<HiveScout> playerModel) {
        playerModel.setAllVisible(true);
        playerModel.crouching = scout.isCrouching();
        HumanoidModel.ArmPose mainHand = armPose(scout, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offHand = armPose(scout, InteractionHand.OFF_HAND);
        if (mainHand.isTwoHanded()) {
            offHand = scout.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (scout.getMainArm() == HumanoidArm.RIGHT) {
            playerModel.rightArmPose = mainHand;
            playerModel.leftArmPose = offHand;
        } else {
            playerModel.rightArmPose = offHand;
            playerModel.leftArmPose = mainHand;
        }
    }

    /** A crouching player is drawn a little lower, as a player is. */
    @Override
    public Vec3 getRenderOffset(HiveScout scout, float partialTicks) {
        return scout.isCrouching() ? new Vec3(0.0D, -2.0D / 16.0D, 0.0D) : super.getRenderOffset(scout, partialTicks);
    }

    /** A swimming player lies along the water, tilted with where they look (the player renderer's own tilt). */
    @Override
    protected void setupRotations(HiveScout scout, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTicks, float scale) {
        float swim = scout.getSwimAmount(partialTicks);
        super.setupRotations(scout, poseStack, ageInTicks, rotationYaw, partialTicks, scale);
        if (swim > 0.0F) {
            float target = scout.isInWater() ? -90.0F - scout.getViewXRot(partialTicks) : -90.0F;
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(swim, 0.0F, target)));
            if (scout.isVisuallySwimming()) {
                poseStack.translate(0.0F, -1.0F, 0.3F);
            }
        }
    }
}
