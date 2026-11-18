package org.minechestplate.mcpskins.config;

/** Corner an overlay element's offset is measured from, so a saved position survives window resizes. */
public enum ScreenAnchor {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    public boolean isRight() {
        return this == TOP_RIGHT || this == BOTTOM_RIGHT;
    }

    public boolean isBottom() {
        return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
    }

    public int resolveX(int screenWidth, int elementWidth, int offsetX) {
        return isRight() ? screenWidth - offsetX - elementWidth : offsetX;
    }

    public int resolveY(int screenHeight, int elementHeight, int offsetY) {
        return isBottom() ? screenHeight - offsetY - elementHeight : offsetY;
    }

    // Inverse of resolveX.
    public int offsetXFor(int screenWidth, int elementWidth, int x0) {
        return isRight() ? screenWidth - elementWidth - x0 : x0;
    }

    // Inverse of resolveY.
    public int offsetYFor(int screenHeight, int elementHeight, int y0) {
        return isBottom() ? screenHeight - elementHeight - y0 : y0;
    }

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