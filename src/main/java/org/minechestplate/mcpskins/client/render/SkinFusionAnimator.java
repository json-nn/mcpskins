package org.minechestplate.mcpskins.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.network.SkinFusionPayload;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Drives every running {@link SkinFusionAnimation}: owns the active list, emits particles and
 * sound cues on the tick thread, and draws the items during the level render pass.
 * <p>
 * Everything here is decorative. The fuse already resolved server-side before the packet that
 * starts an animation was sent, so dropping one for any reason only costs the visuals.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class SkinFusionAnimator {

    /** Enough for a busy server without letting a packet flood turn into unbounded render work. */
    private static final int MAX_ACTIVE = 8;

    /** A player can only fuse once at a time, so anything faster than this is a flood. */
    private static final int REPLACE_COOLDOWN_TICKS = 4;

    private static final double MAX_DISTANCE = 64.0;
    private static final double MAX_DISTANCE_SQR = MAX_DISTANCE * MAX_DISTANCE;

    private static final int UNKNOWN_ACCENT = 0xFFFFFF;

    private static final List<SkinFusionAnimation> ACTIVE = new ArrayList<>();

    private static boolean renderFailed = false;
    private static boolean warnedOnce = false;

    private static int lastCueEntityId = -1;
    private static long lastCueGameTime = Long.MIN_VALUE;

    private SkinFusionAnimator() {
    }


    public static void onFusion(SkinFusionPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        // An entity rather than a raw position, so the effect can only appear on a player the
        // client already tracks, and dies with them.
        Entity entity = mc.level.getEntity(payload.playerId());
        if (!(entity instanceof Player player) || entity.isRemoved()) {
            return;
        }
        if (player.distanceToSqr(mc.player) > MAX_DISTANCE_SQR) {
            return;
        }

        boolean self = player == mc.player;
        if (!self && !MCPSkinsClientConfig.fusionAnimShowOthers()) {
            return;
        }

        long gameTime = mc.level.getGameTime();
        List<String> fusedSkinIds = payload.consumedSkinIds();
        boolean animate = !fusedSkinIds.isEmpty()
                && MCPSkinsClientConfig.fusionAnimEnabled()
                && claimSlot(payload.playerId(), gameTime);
        boolean cue = MCPSkinsClientConfig.fusionAnimSound() && allowCue(payload.playerId(), gameTime);

        if (!animate) {
            // Nothing will reach the reveal, so its payoff fires now instead of being lost.
            if (cue) {
                playAt(mc, player.position(), SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
            }
            return;
        }

        if (cue) {
            playAt(mc, player.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.0f);
        }

        List<ItemStack> ringStacks = new ArrayList<>(fusedSkinIds.size());
        for (String skinId : fusedSkinIds) {
            ringStacks.add(unlockStack(skinId));
        }

        ACTIVE.add(new SkinFusionAnimation(payload.playerId(), payload.mainHand(),
                ringStacks, unlockStack(payload.resultSkinId()),
                accentOf(fusedSkinIds.get(0)), accentOf(payload.resultSkinId()),
                gameTime, MCPSkinsClientConfig.fusionAnimDurationMs() / 50.0));
    }

    /** Separate from {@link #claimSlot}: the cue also fires when the animation is switched off. */
    private static boolean allowCue(int entityId, long gameTime) {
        if (entityId == lastCueEntityId && gameTime - lastCueGameTime < REPLACE_COOLDOWN_TICKS) {
            return false;
        }
        lastCueEntityId = entityId;
        lastCueGameTime = gameTime;
        return true;
    }

    /** Replaces an existing animation for the same player, or refuses when the list is full. */
    private static boolean claimSlot(int entityId, long gameTime) {
        for (int i = 0; i < ACTIVE.size(); i++) {
            SkinFusionAnimation existing = ACTIVE.get(i);
            if (existing.entityId() != entityId) {
                continue;
            }
            if (gameTime - existing.startGameTime() < REPLACE_COOLDOWN_TICKS) {
                return false;
            }
            ACTIVE.remove(i);
            return true;
        }
        return ACTIVE.size() < MAX_ACTIVE;
    }

    private static ItemStack unlockStack(String skinId) {
        ItemStack stack = new ItemStack(ModItems.SKIN_UNLOCK_ITEM.get());
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinToUnlock", skinId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Falls back to white when the registry has not caught up, matching the item tint handler. */
    private static int accentOf(String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
        if (lookup == null) {
            return UNKNOWN_ACCENT;
        }
        return RarityManager.INSTANCE.get(lookup.skin().rarityId()).accentColor();
    }


    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            ACTIVE.clear();
            return;
        }

        long gameTime = mc.level.getGameTime();
        boolean particles = MCPSkinsClientConfig.fusionAnimParticles();
        boolean sound = MCPSkinsClientConfig.fusionAnimSound();

        Iterator<SkinFusionAnimation> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            SkinFusionAnimation animation = iterator.next();
            Player player = resolve(mc, animation);
            if (player == null || animation.finished(gameTime, 0f)) {
                iterator.remove();
                continue;
            }

            float t = animation.progress(gameTime, 0f);
            boolean firstPerson = isFirstPerson(mc, player);
            SkinFusionAnimation.Basis basis = animation.basis(player, 1f, firstPerson);

            SkinFusionAnimation.Phase entered = animation.advanceTickedPhase(t);
            if (entered != null && sound) {
                playPhaseCue(mc, basis.anchor(), entered);
            }
            if (particles) {
                emitParticles(mc, animation, basis, player, firstPerson, t, entered);
            }
        }
    }

    private static void playPhaseCue(Minecraft mc, Vec3 at, SkinFusionAnimation.Phase phase) {
        switch (phase) {
            case COLLAPSE -> playAt(mc, at, SoundEvents.CONDUIT_ACTIVATE, 0.45f, 1.4f);
            case REVEAL -> playAt(mc, at, SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
            default -> {
            }
        }
    }

    private static void playAt(Minecraft mc, Vec3 at, SoundEvent sound, float volume, float pitch) {
        if (mc.level == null) {
            return;
        }
        mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch, false);
    }

    /** On the tick thread on purpose: from the render pass the count would follow frame rate. */
    private static void emitParticles(Minecraft mc, SkinFusionAnimation animation,
                                      SkinFusionAnimation.Basis basis, Player player,
                                      boolean firstPerson, float t,
                                      SkinFusionAnimation.Phase entered) {
        SkinFusionAnimation.Phase phase = SkinFusionAnimation.phaseOf(t);
        int perItem = switch (phase) {
            case LAUNCH -> 2;
            case ORBIT -> 1;
            case COLLAPSE -> 3;
            case REVEAL -> 0;
        };

        for (int index = 0; index < animation.ringSize() && perItem > 0; index++) {
            Vec3 hand = animation.handOrigin(player, 1f, firstPerson, basis, index);
            Vec3 at = animation.itemPosition(basis, hand, index, t);
            for (int n = 0; n < perItem; n++) {
                spawn(mc, animation.fromDust(), at, 0.12);
            }
        }

        if (entered == SkinFusionAnimation.Phase.REVEAL) {
            Vec3 center = animation.centerPosition(basis, t);
            for (int n = 0; n < 28; n++) {
                spawn(mc, animation.toDust(), center, 0.45);
            }
            for (int n = 0; n < 8; n++) {
                spawn(mc, animation.fromDust(), center, 0.30);
            }
        }
    }

    private static void spawn(Minecraft mc, ParticleOptions options, Vec3 at, double spread) {
        if (mc.level == null) {
            return;
        }
        double dx = (mc.level.random.nextDouble() - 0.5) * spread;
        double dy = (mc.level.random.nextDouble() - 0.5) * spread;
        double dz = (mc.level.random.nextDouble() - 0.5) * spread;
        mc.level.addParticle(options, at.x + dx, at.y + dy, at.z + dz, 0.0, 0.0, 0.0);
    }


    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (ACTIVE.isEmpty() || renderFailed) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        long gameTime = mc.level.getGameTime();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        try {
            for (SkinFusionAnimation animation : ACTIVE) {
                Player player = resolve(mc, animation);
                if (player == null || player.distanceToSqr(mc.player) > MAX_DISTANCE_SQR) {
                    continue;
                }
                float t = animation.progress(gameTime, partialTick);
                boolean firstPerson = isFirstPerson(mc, player);
                SkinFusionAnimation.Basis basis = animation.basis(player, partialTick, firstPerson);
                if (!event.getFrustum().isVisible(new AABB(basis.anchor(), basis.anchor()).inflate(2.0))) {
                    continue;
                }
                renderAnimation(mc, animation, basis, player, firstPerson, partialTick, t, camera, pose, buffers);
            }
        } catch (Throwable t) {
            renderFailed = true;
            ACTIVE.clear();
            if (!warnedOnce) {
                warnedOnce = true;
                MCPSkins.LOGGER.warn("[MCPSkins] Skin fusion animation failed to render and has been "
                        + "disabled for this session. The fuse itself is unaffected.", t);
            }
        }

        buffers.endBatch();
    }

    private static void renderAnimation(Minecraft mc, SkinFusionAnimation animation,
                                        SkinFusionAnimation.Basis basis, Player player,
                                        boolean firstPerson, float partialTick, float t, Vec3 camera,
                                        PoseStack pose, MultiBufferSource buffers) {
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(basis.anchor()));

        if (SkinFusionAnimation.phaseOf(t) == SkinFusionAnimation.Phase.REVEAL) {
            Vec3 center = animation.centerPosition(basis, t).add(0.0, animation.revealLift(t), 0.0);
            drawItem(mc, animation.resultStack(), center, camera, pose, buffers, light,
                    animation.revealScale(t), animation.revealSpin(t));
            return;
        }

        float scale = animation.itemScale(t);
        for (int index = 0; index < animation.ringSize(); index++) {
            Vec3 hand = animation.handOrigin(player, partialTick, firstPerson, basis, index);
            Vec3 at = animation.itemPosition(basis, hand, index, t);
            float roll = Mth.RAD_TO_DEG * SkinFusionAnimation.spin(t) * 2f + index * 37f;
            drawItem(mc, animation.ringStack(index), at, camera, pose, buffers, light, scale, roll);
        }
    }

    /** The level renderer owns this PoseStack: an unbalanced push corrupts the rest of the frame. */
    private static void drawItem(Minecraft mc, ItemStack stack, Vec3 at, Vec3 camera,
                                 PoseStack pose, MultiBufferSource buffers,
                                 int light, float scale, float roll) {
        if (scale <= 0f) {
            return;
        }
        pose.pushPose();
        try {
            pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
            pose.scale(scale, scale, scale);
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.GROUND, light,
                    OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
        } finally {
            pose.popPose();
        }
    }


    /** Re-resolved rather than held, so a respawn or a dimension change ends the animation. */
    private static Player resolve(Minecraft mc, SkinFusionAnimation animation) {
        Entity entity = mc.level.getEntity(animation.entityId());
        if (!(entity instanceof Player player) || entity.isRemoved() || entity.level() != mc.level) {
            return null;
        }
        return player;
    }

    private static boolean isFirstPerson(Minecraft mc, Player player) {
        return player == mc.player && mc.options.getCameraType().isFirstPerson();
    }

    public static void reset() {
        ACTIVE.clear();
        renderFailed = false;
        warnedOnce = false;
        lastCueEntityId = -1;
        lastCueGameTime = Long.MIN_VALUE;
    }
}
