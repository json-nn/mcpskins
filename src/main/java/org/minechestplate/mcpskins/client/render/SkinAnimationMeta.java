package org.minechestplate.mcpskins.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.minechestplate.mcpskins.MCPSkins;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code animation} block of a vanilla {@code .png.mcmeta}, with vanilla's shape and
 * defaults: frames are a square grid of {@code min(width, height)} read left to right, top to
 * bottom, so the common vertical strip needs no fields at all.
 * <p>
 * Anything malformed logs one line and leaves the texture static rather than failing the skin.
 */
public record SkinAnimationMeta(int frameWidth, int frameHeight, List<Frame> frames, boolean interpolate) {

    /** @param time how many ticks this frame holds */
    public record Frame(int index, int time) {
    }

    private static final int MAX_FRAMES = 1024;

    public int totalTicks() {
        int total = 0;
        for (Frame frame : frames) {
            total += frame.time();
        }
        return total;
    }

    /**
     * @return the parsed animation, or null when the file has no {@code animation} block, is
     *         malformed, or does not fit the image
     */
    public static SkinAnimationMeta parse(byte[] mcmetaBytes, int imageWidth, int imageHeight, String debugName) {
        try {
            JsonElement root = JsonParser.parseString(new String(mcmetaBytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return null;
            JsonObject json = root.getAsJsonObject();
            if (!json.has("animation") || !json.get("animation").isJsonObject()) return null;
            JsonObject animation = json.getAsJsonObject("animation");

            int defaultTime = Math.max(1, optInt(animation, "frametime", 1));
            boolean interpolate = animation.has("interpolate") && animation.get("interpolate").getAsBoolean();

            // Vanilla's rule: an unset axis falls back to the image, and unset both means square.
            int declaredW = optInt(animation, "width", -1);
            int declaredH = optInt(animation, "height", -1);
            int frameWidth;
            int frameHeight;
            if (declaredW > 0) {
                frameWidth = declaredW;
                frameHeight = declaredH > 0 ? declaredH : imageHeight;
            } else if (declaredH > 0) {
                frameWidth = imageWidth;
                frameHeight = declaredH;
            } else {
                frameWidth = frameHeight = Math.min(imageWidth, imageHeight);
            }

            if (frameWidth <= 0 || frameHeight <= 0
                    || imageWidth % frameWidth != 0 || imageHeight % frameHeight != 0) {
                warn(debugName, "frame size " + frameWidth + "x" + frameHeight
                        + " does not divide the " + imageWidth + "x" + imageHeight + " image");
                return null;
            }

            int columns = imageWidth / frameWidth;
            int available = columns * (imageHeight / frameHeight);
            if (available < 2) {
                warn(debugName, "only " + available + " frame(s) fit, so there is nothing to animate");
                return null;
            }

            List<Frame> frames = readFrames(animation, available, defaultTime, debugName);
            if (frames == null || frames.isEmpty()) return null;
            return new SkinAnimationMeta(frameWidth, frameHeight, List.copyOf(frames), interpolate);
        } catch (RuntimeException e) {
            warn(debugName, "could not be parsed (" + e.getMessage() + ")");
            return null;
        }
    }

    private static List<Frame> readFrames(JsonObject animation, int available, int defaultTime, String debugName) {
        List<Frame> frames = new ArrayList<>();
        if (!animation.has("frames")) {
            for (int i = 0; i < available; i++) {
                frames.add(new Frame(i, defaultTime));
            }
            return frames;
        }

        for (JsonElement element : animation.getAsJsonArray("frames")) {
            int index;
            int time = defaultTime;
            if (element.isJsonObject()) {
                JsonObject frame = element.getAsJsonObject();
                index = frame.get("index").getAsInt();
                time = Math.max(1, optInt(frame, "time", defaultTime));
            } else {
                index = element.getAsInt();
            }
            if (index < 0 || index >= available) {
                warn(debugName, "frame index " + index + " is outside the " + available + " frames in the image");
                return null;
            }
            if (frames.size() >= MAX_FRAMES) {
                warn(debugName, "more than " + MAX_FRAMES + " frames; ignoring the animation");
                return null;
            }
            frames.add(new Frame(index, time));
        }
        return frames;
    }

    private static int optInt(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static void warn(String debugName, String problem) {
        MCPSkins.LOGGER.warn("[MCPSkins] Animation metadata for '{}' {}; the texture stays static.",
                debugName, problem);
    }
}
