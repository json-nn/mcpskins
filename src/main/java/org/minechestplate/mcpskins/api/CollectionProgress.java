package org.minechestplate.mcpskins.api;

/**
 * How far a player is through one collection.
 *
 * @param total 0 for a collection no loaded pack defines
 */
public record CollectionProgress(String collection, int owned, int total) {

    public boolean complete() {
        return total > 0 && owned >= total;
    }
}
