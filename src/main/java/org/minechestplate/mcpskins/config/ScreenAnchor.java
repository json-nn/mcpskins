package org.minechestplate.mcpskins.config;

/**
 * Screen corner a movable overlay element's position is measured from. Storing an
 * anchor + offset instead of raw pixels keeps a saved position sane across window sizes.
 */
public enum ScreenAnchor {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    /** Top-left X of an element of the given width, offset by {@code offsetX} from this anchor. */
    public int resolveX(int screenWidth, int elementWidth, int offsetX) {
        return isRight() ? screenWidth - offsetX - elementWidth : offsetX;
    }

    /** Top-left Y of an element of the given height, offset by {@code offsetY} from this anchor. */
    public int resolveY(int screenHeight, int elementHeight, int offsetY) {
        return isBottom() ? screenHeight - offsetY - elementHeight : offsetY;
    }

    /** Inverse of {@link #resolveX}: the offset that reproduces {@code x0} from this anchor. */
    public int offsetXFor(int screenWidth, int elementWidth, int x0) {
        return isRight() ? screenWidth - elementWidth - x0 : x0;
    }

    /** Inverse of {@link #resolveY}: the offset that reproduces {@code y0} from this anchor. */
    public int offsetYFor(int screenHeight, int elementHeight, int y0) {
        return isBottom() ? screenHeight - elementHeight - y0 : y0;
    }

    /** Corner closest to an element's current position - used to re-anchor after a drag. */
    public static ScreenAnchor nearest(int x0, int y0, int elementWidth, int elementHeight,
                                       int screenWidth, int screenHeight) {
        boolean left = (x0 + elementWidth / 2) < screenWidth / 2;
        boolean top = (y0 + elementHeight / 2) < screenHeight / 2;
        if (top) {
            return left ? TOP_LEFT : TOP_RIGHT;
        }
        return left ? BOTTOM_LEFT : BOTTOM_RIGHT;
    }
}