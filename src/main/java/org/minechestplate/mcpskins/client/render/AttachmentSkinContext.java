package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

/**
 * Carries a stack's skin id into TACZ's {@code getClientAttachmentIndex}, which is keyed by
 * attachment id alone; every caller reads the id off the stack just before. A mismatched id is
 * dropped, so a stale entry never lands on another attachment. Render thread only.
 */
public final class AttachmentSkinContext {

    private record Pending(String attachmentId, String skinId) {
    }

    // One field, so a read can never pair one stack's id with another's skin.
    private static volatile Pending pending;

    private AttachmentSkinContext() {
    }

    public static void record(ItemStack stack, ResourceLocation attachmentId) {
        String skinId = TACZSkinHelper.getSkinId(stack);
        pending = skinId == null || attachmentId == null
                ? null
                : new Pending(attachmentId.toString(), skinId);
    }

    public static String consume(ResourceLocation attachmentId) {
        Pending current = pending;
        pending = null;
        if (current == null || attachmentId == null) return null;
        return attachmentId.toString().equals(current.attachmentId()) ? current.skinId() : null;
    }
}
