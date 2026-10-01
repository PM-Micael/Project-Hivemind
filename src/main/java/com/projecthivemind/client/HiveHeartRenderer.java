package com.projecthivemind.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;

/** Draws the Hive Heart as a pulsing block. Placeholder look: a nether wart block. */
public class HiveHeartRenderer extends EntityRenderer<HiveHeart> {
    private final BlockRenderDispatcher blockRenderer;

    public HiveHeartRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = context.getBlockRenderDispatcher();
        this.shadowRadius = 0.5F;
    }

    @Override
    public void render(HiveHeart heart, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        // Beat slowly, scaling around the block's centre. The entity's origin is the middle of its base.
        float beat = 1.0F + 0.06F * Mth.sin((heart.tickCount + partialTick) * 0.3F);
        poseStack.translate(0.0D, 0.5D, 0.0D);
        poseStack.scale(beat, beat, beat);
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        blockRenderer.renderSingleBlock(Blocks.NETHER_WART_BLOCK.defaultBlockState(), poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        super.render(heart, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(HiveHeart heart) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
