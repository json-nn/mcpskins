package org.minechestplate.mcpskins.client.gui;

import net.minecraft.util.Mth;

/**
 * Places a hover label under an element, keeping it on screen: centers when there's room,
 * otherwise hugs whichever edge has room, and flips above the element if it would run off
 * the bottom.
 */
public final class TooltipPlacement {

    public record Result(int x, int y) {
    }

    private TooltipPlacement() {
    }

    public static Result compute(int elementX0, int elementX1, int elementY0, int elementY1,
                                 int labelWidth, int labelHeight,
                                 int screenWidth, int screenHeight, int margin) {
        int centerX = (elementX0 + elementX1) / 2;
        int centered = centerX - labelWidth / 2;

        int x;
        if (centered >= margin && centered + labelWidth <= screenWidth - margin) {
            x = centered;
        } else if (centered < margin) {
            x = elementX0;
        } else {
            x = elementX1 - labelWidth;
        }
        x = Mth.clamp(x, margin, Math.max(margin, screenWidth - margin - labelWidth));

        int below = elementY1 + 4;
        int y = (below + labelHeight <= screenHeight - margin) ? below : elementY0 - labelHeight - 4;
        return new Result(x, y);
    }
}