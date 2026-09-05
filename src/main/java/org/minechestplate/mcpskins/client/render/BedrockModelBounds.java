package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BonesItem;
import com.tacz.guns.client.resource.pojo.model.CubesItem;
import com.tacz.guns.client.resource.pojo.model.GeometryModelNew;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Bounding box of a Bedrock model's cubes, in Bedrock units, used to centre and size the
 * Armory's 3D preview.
 * <p>
 * Helper bones are skipped. TACZ builds a scope's reticle as a plane over a hundred units from
 * the optic and its laser as a beam running off into the distance, neither of which is part of
 * the shape a player sees on the item. Counting them puts the centre nowhere near the model and
 * makes the fit useless, which is what threw scopes out of frame.
 * <p>
 * Per-cube rotations are ignored, which only has to be close enough to frame a preview.
 */
public final class BedrockModelBounds {

    /** Roots come from {@code BedrockAttachmentModel}; the numbered forms are its own suffixes. */
    private static final Pattern HELPER_ROOT =
            Pattern.compile("^(division|laser_beam|scope_view)(_\\d+)?$");

    public record Bounds(Vec3 min, Vec3 max) {

        public Vec3 center() {
            return min.add(max).scale(0.5);
        }

        /** Farthest horizontal distance from {@code pivot} to the box, the radius a spin sweeps. */
        public double horizontalRadius(Vec3 pivot) {
            return Math.hypot(Math.max(max.x - pivot.x, pivot.x - min.x),
                    Math.max(max.z - pivot.z, pivot.z - min.z));
        }

        public double verticalRadius(Vec3 pivot) {
            return Math.max(max.y - pivot.y, pivot.y - min.y);
        }
    }

    private static final Map<ResourceLocation, Bounds> CACHE = new ConcurrentHashMap<>();

    private BedrockModelBounds() {
    }

    /** Null until TACZ has registered the model, so callers retry rather than pin a bad value. */
    public static Bounds of(ResourceLocation modelLocation) {
        if (modelLocation == null) return null;
        Bounds cached = CACHE.get(modelLocation);
        if (cached != null) return cached;

        Bounds computed = compute(modelLocation);
        if (computed != null) {
            CACHE.put(modelLocation, computed);
        }
        return computed;
    }

    public static Vec3 center(ResourceLocation modelLocation) {
        Bounds bounds = of(modelLocation);
        return bounds == null ? Vec3.ZERO : bounds.center();
    }

    private static Bounds compute(ResourceLocation modelLocation) {
        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelLocation);
        if (pojo == null) return null;
        GeometryModelNew geometry = pojo.getGeometryModelNew();
        if (geometry == null || geometry.getBones() == null) return null;

        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        boolean any = false;

        Map<String, String> parents = new HashMap<>();
        for (BonesItem bone : geometry.getBones()) {
            parents.put(bone.getName(), bone.getParent());
        }

        for (BonesItem bone : geometry.getBones()) {
            if (bone.getCubes() == null || isHelper(bone.getName(), parents)) continue;
            for (CubesItem cube : bone.getCubes()) {
                List<Float> origin = cube.getOrigin();
                List<Float> size = cube.getSize();
                if (origin == null || size == null || origin.size() < 3 || size.size() < 3) continue;
                for (int axis = 0; axis < 3; axis++) {
                    double low = origin.get(axis);
                    double high = low + size.get(axis);
                    min[axis] = Math.min(min[axis], Math.min(low, high));
                    max[axis] = Math.max(max[axis], Math.max(low, high));
                }
                any = true;
            }
        }
        if (!any) return null;

        return new Bounds(new Vec3(min[0], min[1], min[2]), new Vec3(max[0], max[1], max[2]));
    }

    /**
     * Walks up the hierarchy, because the parts hung off a helper are named freely.
     * {@code division_illuminated} is a child of {@code division} and just as far away.
     */
    private static boolean isHelper(String name, Map<String, String> parents) {
        for (int depth = 0; name != null && depth < 32; depth++) {
            if (HELPER_ROOT.matcher(name).matches()) return true;
            name = parents.get(name);
        }
        return false;
    }

    public static void clear() {
        CACHE.clear();
    }
}
