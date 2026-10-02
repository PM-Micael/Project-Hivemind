package com.projecthivemind.client;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.slf4j.Logger;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.network.SyncEyesPayload.EyePoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Fog of war for terrain, in the RTS view. Everything the hive's eyes (the Heart and each unit) cannot see is
 * darkened; what they can see is drawn normally.
 *
 * <p>How it works, in three parts:
 * <ol>
 *   <li>The server tells the client where the eyes are and how far each sees ({@link ClientSight#eyes()}).</li>
 *   <li>This class works out, for every column of blocks around the camera, whether an eye has a clear line to its
 *       surface (opaque blocks stop the line, exactly like the server's Hive Sight). The answer goes into a
 *       {@value #SIZE} by {@value #SIZE} texture, one pixel per column. The work is spread over frames, so it never
 *       stalls the game, and the texture is replaced when a whole pass has finished.</li>
 *   <li>After the world is drawn, a full-screen pass rebuilds each pixel's world position from the depth buffer, looks
 *       its column up in the texture, and darkens it if it is not seen. See {@code shaders/core/hive_fog.fsh}.</li>
 * </ol>
 *
 * <p>It is a purely visual effect on this client, and always on while playing as the hive.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class FogOfWar {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The grid is this many blocks (and pixels) wide, centred on the camera, so it reaches 256 blocks each way. */
    private static final int SIZE = 512;
    /** The grid is re-centred when the camera has moved this far from its middle. */
    private static final int RECENTER_DISTANCE = 64;
    /** Most time spent on visibility work in a single frame. */
    private static final long FRAME_BUDGET_NANOS = 2_500_000L;
    /** Never start a new pass sooner than this after the last one began. */
    private static final long MIN_PASS_GAP_NANOS = 200_000_000L;
    /** Columns this far from every eye are worked out four at a time, since they are far from the action. */
    private static final double COARSE_DISTANCE = 40.0D;
    /** A column this close to an eye is always seen, whatever the walls say. */
    private static final double ALWAYS_SEEN = 2.5D;

    @Nullable
    private static ShaderInstance shader;
    @Nullable
    private static DynamicTexture texture;
    @Nullable
    private static TextureTarget depthCopy;

    /** Where the texture on screen has its corner, in world blocks. Valid once {@link #hasTexture} is set. */
    private static int shownOriginX;
    private static int shownOriginZ;
    private static boolean hasTexture;

    // The pass being worked on.
    private static final byte[] WORK = new byte[SIZE * SIZE];
    private static boolean passActive;
    private static int passCursor;
    private static int passOriginX;
    private static int passOriginZ;
    private static List<EyePoint> passEyes = List.of();
    private static int passVersion = -1;
    private static int doneVersion = -1;
    private static long lastPassStart;

    private FogOfWar() {
    }



    @SubscribeEvent
    static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), ProjectHivemind.id("hive_fog"), DefaultVertexFormat.POSITION),
                    loaded -> shader = loaded);
        } catch (IOException e) {
            LOGGER.error("Could not load the fog of war shader; terrain fog is disabled", e);
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        passActive = false;
        hasTexture = false;
        doneVersion = -1;
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (shader == null || minecraft.level == null || !ClientState.hiveMode() || !ClientConfig.fogOfWar()
                || ClientSight.eyes().isEmpty()) {
            passActive = false;
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        work(minecraft.level, camera);
        if (hasTexture) {
            draw(minecraft, event, camera);
        }
    }

    // ---- working out what is seen ----

    /** Start a pass if one is due, and spend this frame's share of the time on the one in progress. */
    private static void work(ClientLevel level, Vec3 camera) {
        long now = System.nanoTime();
        if (!passActive) {
            boolean eyesChanged = ClientSight.eyesVersion() != doneVersion;
            boolean offCentre = hasTexture && (Math.abs(camera.x - (shownOriginX + SIZE / 2)) > RECENTER_DISTANCE
                    || Math.abs(camera.z - (shownOriginZ + SIZE / 2)) > RECENTER_DISTANCE);
            boolean due = !hasTexture || (now - lastPassStart >= MIN_PASS_GAP_NANOS);
            if (!(eyesChanged || offCentre) || !due) {
                return;
            }
            passActive = true;
            passCursor = 0;
            passVersion = ClientSight.eyesVersion();
            passEyes = ClientSight.eyes();
            // Snapped to whole chunks so the grid only ever shifts by a few whole steps.
            passOriginX = Mth.floor(camera.x / 16.0D) * 16 - SIZE / 2;
            passOriginZ = Mth.floor(camera.z / 16.0D) * 16 - SIZE / 2;
            lastPassStart = now;
        }

        long deadline = now + FRAME_BUDGET_NANOS;
        int total = SIZE * SIZE;
        while (passCursor < total) {
            // Checking the clock for every column would cost more than the column; every 64th is plenty.
            if ((passCursor & 63) == 0 && System.nanoTime() > deadline) {
                return;
            }
            WORK[passCursor] = evaluateCell(level, passCursor);
            passCursor++;
        }
        finishPass();
    }

    /** The visibility of one cell of the grid: 0 for fogged, anything else for seen. */
    private static byte evaluateCell(ClientLevel level, int index) {
        int cx = index % SIZE;
        int cz = index / SIZE;
        int wx = passOriginX + cx;
        int wz = passOriginZ + cz;

        double nearest = Double.MAX_VALUE;
        for (EyePoint eye : passEyes) {
            double dx = wx + 0.5D - eye.x();
            double dz = wz + 0.5D - eye.z();
            // Reaching an eye's radius sideways is not enough on its own, but not reaching it is always fogged.
            if (Math.abs(dx) <= eye.radius() && Math.abs(dz) <= eye.radius()) {
                nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
            }
        }
        if (nearest == Double.MAX_VALUE) {
            return 0;
        }
        // Far from every eye, work out one column in four and share the answer.
        if (nearest > COARSE_DISTANCE && ((cx & 1) != 0 || (cz & 1) != 0)) {
            return WORK[(cz & ~1) * SIZE + (cx & ~1)];
        }
        return columnSeen(level, passEyes, wx, wz) ? (byte) 1 : 0;
    }

    private static boolean columnSeen(ClientLevel level, List<EyePoint> eyes, int wx, int wz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) {
            return false;
        }
        // ChunkAccess.getHeight is the Y of the highest blocking block; the open air above it starts one higher.
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15) + 1;
        double centreX = wx + 0.5D;
        double centreZ = wz + 0.5D;
        Vec3 surface = new Vec3(centreX, top + 0.05D, centreZ);

        for (EyePoint eye : eyes) {
            double dx = eye.x() - centreX;
            double dz = eye.z() - centreZ;
            if (dx * dx + dz * dz <= ALWAYS_SEEN * ALWAYS_SEEN) {
                return true;
            }
            Vec3 from = new Vec3(eye.x(), eye.y(), eye.z());
            if (from.distanceToSqr(surface) > (double) eye.radius() * eye.radius()) {
                continue;
            }
            if (clear(level, from, surface)) {
                return true;
            }
            // A wall seen from the side: its top is hidden from low down, but its face is not. Look at the face that
            // points towards the eye, halfway down the top block.
            double sx = 0.0D;
            double sz = 0.0D;
            if (Math.abs(dx) > Math.abs(dz)) {
                sx = Math.signum(dx);
            } else {
                sz = Math.signum(dz);
            }
            if (clear(level, from, new Vec3(centreX + sx * 0.55D, top - 0.5D, centreZ + sz * 0.55D))) {
                return true;
            }
        }
        return false;
    }

    /** Nothing opaque between the two points. Unloaded chunks block the line. Same rule as the server's Hive Sight. */
    private static boolean clear(ClientLevel level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return Boolean.FALSE;
            }
            BlockState state = chunk.getBlockState(pos);
            return state.isSolidRender(world, pos) ? Boolean.FALSE : null;
        }, world -> Boolean.TRUE);
    }

    /** A whole pass is done: put its answer on the texture the shader reads. */
    private static void finishPass() {
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, true);
            texture.setFilter(true, false);
        }
        NativeImage image = texture.getPixels();
        if (image != null) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    // Pixel format is ABGR: seen is white, fogged is black, both opaque. The shader reads red.
                    image.setPixelRGBA(x, z, WORK[z * SIZE + x] != 0 ? 0xFFFFFFFF : 0xFF000000);
                }
            }
            texture.upload();
            shownOriginX = passOriginX;
            shownOriginZ = passOriginZ;
            hasTexture = true;
        }
        doneVersion = passVersion;
        passActive = false;
    }

    // ---- drawing ----

    private static void draw(Minecraft minecraft, RenderLevelStageEvent event, Vec3 camera) {
        ShaderInstance fog = shader;
        if (fog == null || texture == null) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        // The depth buffer cannot be read while it is also being drawn into, so read a copy of it.
        if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height) {
            if (depthCopy != null) {
                depthCopy.destroyBuffers();
            }
            depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        }
        depthCopy.copyDepthFrom(main);
        main.bindWrite(true);

        fog.safeGetUniform("InvProj").set(new Matrix4f(event.getProjectionMatrix()).invert());
        fog.safeGetUniform("InvView").set(new Matrix4f(event.getModelViewMatrix()).invert());
        fog.safeGetUniform("CameraPos").set((float) camera.x, (float) camera.y, (float) camera.z);
        fog.safeGetUniform("Grid").set((float) shownOriginX, (float) shownOriginZ, (float) SIZE, 0.0F);
        fog.setSampler("DepthSampler", depthCopy.getDepthTextureId());
        fog.setSampler("VisSampler", texture.getId());

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> fog);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
