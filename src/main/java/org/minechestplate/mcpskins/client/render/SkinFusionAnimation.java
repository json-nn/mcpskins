package org.minechestplate.mcpskins.client.render;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * One running fusion effect: the consumed items lob out of the hands, orbit a point in front of
 * the player while the ring contracts, collapse to the center, and the rolled skin pops out.
 * <p>
 * Pure state and geometry. It never touches the render pipeline or the config, so the caller
 * decides when to draw and how long the whole thing lasts. Everything derives from a single
 * normalized progress value, which keeps the phases from drifting apart at odd durations.
 */
public final class SkinFusionAnimation {

    public enum Phase {
        LAUNCH,
        ORBIT,
        COLLAPSE,
        REVEAL
    }

    private static final float LAUNCH_END = 0.18f;
    private static final float ORBIT_END = 0.68f;
    private static final float COLLAPSE_END = 0.86f;

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    // Cumulative turns rather than a per-phase angular velocity. Velocity steps would show up
    // as a visible hitch every time the phase changes.
    private static final float SPIN_BASE_TURNS = 0.75f;
    private static final float SPIN_ACCEL_TURNS = 2.25f;

    private static final double RADIUS_MAX = 1.15;
    private static final double RADIUS_MID = 0.63;
    private static final double RADIUS_END = 0.06;

    private static final double RISE = 0.35;
    private static final double BOB = 0.12;

    // Tips the ring off vertical so it reads as a disc in space instead of a flat cutout.
    private static final double TILT_BASE = 0.35;
    private static final double TILT_WOBBLE = 0.20;

    private static final double ANCHOR_HEIGHT = 0.85;
    private static final double ANCHOR_FORWARD = 0.85;
    private static final double HAND_HEIGHT = 0.62;
    private static final double HAND_FORWARD = 0.30;
    private static final double HAND_SIDE = 0.42;
    private static final double LOB_HEIGHT = 0.55;

    // The chest anchor sits inside the near plane in first person, so aim off the eye along the
    // full look vector instead. That also keeps the ring in frame when looking up or down.
    private static final double EYE_FORWARD = 1.60;
    private static final double EYE_DROP = 0.25;
    private static final double EYE_HAND_FORWARD = 0.40;
    private static final double EYE_HAND_DROP = 0.35;

    private static final float RING_SCALE = 1.40f;
    private static final float COLLAPSE_SCALE = 0.45f;
    private static final float REVEAL_SCALE = 2.20f;
    private static final float REVEAL_SHRINK_START = 0.85f;
    private static final float REVEAL_SHRINK_TO = 0.70f;
    private static final double REVEAL_LIFT = 0.15;

    /** Anchor point of the ring plus the two horizontal axes it is built on. */
    public record Basis(Vec3 anchor, Vec3 right, Vec3 forward) {
    }

    private final int entityId;
    private final boolean mainHand;
    private final int ringSize;
    private final ItemStack ringStack;
    private final ItemStack resultStack;
    private final DustParticleOptions fromDust;
    private final DustParticleOptions toDust;
    private final long startGameTime;
    private final double durationTicks;

    private Phase lastTickedPhase;

    public SkinFusionAnimation(int entityId, boolean mainHand, int ringSize,
                               ItemStack ringStack, ItemStack resultStack,
                               int fromAccent, int toAccent,
                               long startGameTime, double durationTicks) {
        this.entityId = entityId;
        this.mainHand = mainHand;
        this.ringSize = Math.max(1, ringSize);
        this.ringStack = ringStack;
        this.resultStack = resultStack;
        this.fromDust = new DustParticleOptions(rgb(fromAccent), 0.9f);
        this.toDust = new DustParticleOptions(rgb(toAccent), 1.3f);
        this.startGameTime = startGameTime;
        this.durationTicks = Math.max(1.0, durationTicks);
    }

    private static Vector3f rgb(int color) {
        return new Vector3f(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f);
    }

    public int entityId() {
        return entityId;
    }

    public int ringSize() {
        return ringSize;
    }

    public ItemStack ringStack() {
        return ringStack;
    }

    public ItemStack resultStack() {
        return resultStack;
    }

    public DustParticleOptions fromDust() {
        return fromDust;
    }

    public DustParticleOptions toDust() {
        return toDust;
    }

    public long startGameTime() {
        return startGameTime;
    }

    /**
     * Absolute elapsed time against an absolute start, so a lag spike jumps progress forward
     * rather than letting an accumulator drift. Freezes with the game, since both game time and
     * the partial tick stop while paused.
     */
    public float progress(long gameTime, float partialTick) {
        double elapsed = (gameTime - startGameTime) + partialTick;
        if (elapsed < 0.0) {
            return 1.0f;
        }
        return (float) Mth.clamp(elapsed / durationTicks, 0.0, 1.0);
    }

    public boolean finished(long gameTime, float partialTick) {
        return progress(gameTime, partialTick) >= 1.0f;
    }

    /** Returns the phase if it changed since the last call, otherwise null. Drives one-shot cues. */
    public Phase advanceTickedPhase(float t) {
        Phase phase = phaseOf(t);
        if (phase == lastTickedPhase) {
            return null;
        }
        lastTickedPhase = phase;
        return phase;
    }

    public static Phase phaseOf(float t) {
        if (t < LAUNCH_END) {
            return Phase.LAUNCH;
        }
        if (t < ORBIT_END) {
            return Phase.ORBIT;
        }
        if (t < COLLAPSE_END) {
            return Phase.COLLAPSE;
        }
        return Phase.REVEAL;
    }

    public static float localProgress(float t, Phase phase) {
        float local = switch (phase) {
            case LAUNCH -> t / LAUNCH_END;
            case ORBIT -> (t - LAUNCH_END) / (ORBIT_END - LAUNCH_END);
            case COLLAPSE -> (t - ORBIT_END) / (COLLAPSE_END - ORBIT_END);
            case REVEAL -> (t - COLLAPSE_END) / (1f - COLLAPSE_END);
        };
        return Mth.clamp(local, 0f, 1f);
    }

    public Basis basis(Player player, float partialTick, boolean firstPerson) {
        Vec3 look = player.getViewVector(partialTick);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
        Vec3 right = new Vec3(-flat.z, 0.0, flat.x);

        if (firstPerson) {
            Vec3 anchor = player.getEyePosition(partialTick)
                    .add(look.scale(EYE_FORWARD))
                    .add(0.0, -EYE_DROP, 0.0);
            return new Basis(anchor, right, flat);
        }

        Vec3 base = player.getPosition(partialTick);
        Vec3 anchor = new Vec3(base.x, base.y + player.getBbHeight() * ANCHOR_HEIGHT, base.z)
                .add(flat.scale(ANCHOR_FORWARD));
        return new Basis(anchor, right, flat);
    }

    /**
     * Alternates sides so the ring looks like it came out of both hands. The real hand render
     * position is not reachable from the level render pass, and launch is short enough that the
     * approximation does not read as wrong.
     */
    public Vec3 handOrigin(Player player, float partialTick, boolean firstPerson, Basis basis, int index) {
        double side = ((index & 1) == 0) == mainHand ? HAND_SIDE : -HAND_SIDE;

        if (firstPerson) {
            return player.getEyePosition(partialTick)
                    .add(basis.forward().scale(EYE_HAND_FORWARD))
                    .add(basis.right().scale(side * 0.8))
                    .add(0.0, -EYE_HAND_DROP, 0.0);
        }

        Vec3 base = player.getPosition(partialTick);
        return new Vec3(base.x, base.y + player.getBbHeight() * HAND_HEIGHT, base.z)
                .add(basis.forward().scale(HAND_FORWARD))
                .add(basis.right().scale(side));
    }

    public Vec3 itemPosition(Basis basis, Vec3 handOrigin, int index, float t) {
        Phase phase = phaseOf(t);
        float p = localProgress(t, phase);
        Vec3 lifted = basis.anchor().add(0.0, rise(phase, p), 0.0);
        double angle = TWO_PI * index / ringSize + spin(t);
        Vec3 slot = ringPoint(lifted, basis, angle, radius(phase, p), t);

        if (phase != Phase.LAUNCH) {
            return slot;
        }

        // Lerping toward the live slot, spin included, means the item slides into a moving
        // orbit instead of snapping when launch ends.
        return handOrigin.lerp(slot, easeOutCubic(p))
                .add(0.0, LOB_HEIGHT * Math.sin(Math.PI * p), 0.0);
    }

    public Vec3 centerPosition(Basis basis, float t) {
        Phase phase = phaseOf(t);
        return basis.anchor().add(0.0, rise(phase, localProgress(t, phase)), 0.0);
    }

    public float itemScale(float t) {
        Phase phase = phaseOf(t);
        float p = localProgress(t, phase);
        return switch (phase) {
            case LAUNCH, ORBIT -> RING_SCALE;
            case COLLAPSE -> Mth.lerp(easeInCubic(p), RING_SCALE, COLLAPSE_SCALE);
            case REVEAL -> 0f;
        };
    }

    public float revealScale(float t) {
        float p = localProgress(t, Phase.REVEAL);
        float scale = REVEAL_SCALE * easeOutBack(p);
        if (p > REVEAL_SHRINK_START) {
            scale *= Mth.lerp((p - REVEAL_SHRINK_START) / (1f - REVEAL_SHRINK_START), 1f, REVEAL_SHRINK_TO);
        }
        return Math.max(0f, scale);
    }

    public float revealSpin(float t) {
        return 360f * easeOutQuint(localProgress(t, Phase.REVEAL));
    }

    public double revealLift(float t) {
        return REVEAL_LIFT * easeOutCubic(localProgress(t, Phase.REVEAL));
    }

    public static float spin(float t) {
        return TWO_PI * (SPIN_BASE_TURNS * t + SPIN_ACCEL_TURNS * t * t * t);
    }

    private static double radius(Phase phase, float p) {
        return switch (phase) {
            case LAUNCH -> RADIUS_MAX * easeOutCubic(p);
            case ORBIT -> Mth.lerp(easeInOutCubic(p), RADIUS_MAX, RADIUS_MID);
            case COLLAPSE -> Mth.lerp(easeInCubic(p), RADIUS_MID, RADIUS_END);
            case REVEAL -> RADIUS_END;
        };
    }

    private static double rise(Phase phase, float p) {
        return switch (phase) {
            case LAUNCH -> RISE * easeOutCubic(p);
            case ORBIT -> RISE + BOB * Math.sin(p * TWO_PI);
            case COLLAPSE -> RISE * (1.0 - 0.25 * easeInCubic(p));
            case REVEAL -> RISE * 0.75;
        };
    }

    private static Vec3 ringPoint(Vec3 anchor, Basis basis, double angle, double radius, float t) {
        double tilt = TILT_BASE + TILT_WOBBLE * Math.sin(t * TWO_PI * 1.5);
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return anchor
                .add(basis.right().scale(c * radius))
                .add(0.0, s * radius * Math.cos(tilt), 0.0)
                .add(basis.forward().scale(s * radius * Math.sin(tilt)));
    }

    private static float easeOutCubic(float p) {
        float q = 1f - p;
        return 1f - q * q * q;
    }

    private static float easeInCubic(float p) {
        return p * p * p;
    }

    private static float easeInOutCubic(float p) {
        return p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3.0) / 2f;
    }

    private static float easeOutQuint(float p) {
        float q = 1f - p;
        return 1f - q * q * q * q * q;
    }

    /** Overshoots past the target before settling, which gives the reveal its pop. */
    private static float easeOutBack(float p) {
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        float q = p - 1f;
        return 1f + c3 * q * q * q + c1 * q * q;
    }
}
