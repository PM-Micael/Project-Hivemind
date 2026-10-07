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
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the Hive Heart as a mass of blocks that grows with the hive: 1 block at level 1, 2 tall at level 2, 3 wide by 2 tall at
 * level 3, 3 by 3 tall at level 4, 5 wide by 3 tall at level 5. A beat runs through it from the middle outwards. Placeholder look:
 * nether wart blocks, with shroomlight showing through now and then.
 */
public class HiveHeartRenderer extends EntityRenderer<HiveHeart> {
    private final BlockRenderDispatcher blockRenderer;

    public HiveHeartRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(HiveHeart heart, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        int level = heart.visualLevel();
        int width = HiveHeart.widthAt(level);
        int height = HiveHeart.heightAt(level);
        this.shadowRadius = width / 2.0F;
        float time = (heart.tickCount + partialTick) * 0.3F;
        int reach = width / 2;

        // The entity's origin is the middle of its base: blocks are laid out from there.
        for (int y = 0; y < height; y++) {
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    // The blocks inside are never seen.
                    boolean inside = Math.abs(x) < reach && Math.abs(z) < reach && y > 0 && y < height - 1;
                    if (width >= 3) {
                        // A bulb: a full-size base that narrows into a dome, shell only.
                        inside = !inOrb(x, y, z, reach, height)
                                || (inOrb(x + 1, y, z, reach, height) && inOrb(x - 1, y, z, reach, height)
                                        && inOrb(x, y + 1, z, reach, height) && inOrb(x, y - 1, z, reach, height)
                                        && inOrb(x, y, z + 1, reach, height) && inOrb(x, y, z - 1, reach, height));
                    }
                    if (inside) {
                        continue;
                    }
                    float distance = Mth.sqrt(x * x + y * y * 0.5F + z * z);
                    float beat = 1.0F + 0.05F * (0.5F + 0.5F * Mth.sin(time - distance * 0.9F));
                    BlockState state = (x + y * 3 + z * 7) % 5 == 0 && (x != 0 || z != 0 || y != 0) ? Blocks.SHROOMLIGHT.defaultBlockState()
                            : Blocks.NETHER_WART_BLOCK.defaultBlockState();
                    poseStack.pushPose();
                    poseStack.translate(x, y + 0.5D, z);
                    poseStack.scale(beat, beat, beat);
                    poseStack.translate(-0.5D, -0.5D, -0.5D);
                    blockRenderer.renderSingleBlock(state, poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
                    poseStack.popPose();
                }
            }
        }
        super.render(heart, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    /**
     * Whether a block (y counted from the base) is part of the bulb: the bottom layer keeps the full square of the Heart's size, and the
     * layers above narrow along a dome until the top. Below the base counts as ground, so the underside is never drawn.
     */
    private static boolean inOrb(int x, int y, int z, int reach, int height) {
        if (y < 0 || y == 0) {
            return Math.abs(x) <= reach && Math.abs(z) <= reach;
        }
        if (y >= height) {
            return false;
        }
        float ratio = (float) y / height;
        float limit = (reach + 0.5F) * Mth.sqrt(1.0F - ratio * ratio);
        return x * x + z * z <= limit * limit;
    }

    @Override
    public ResourceLocation getTextureLocation(HiveHeart heart) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
