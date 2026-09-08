package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

/**
 * Carries a stack's skin id across TACZ's {@code getClientAttachmentIndex}, which is keyed by
 * attachment id alone. Every caller reads the id off a stack immediately before that lookup, so
 * recording here covers the item renderer, the gun-mounted renderer, the refit screen and
 * tooltips at once. A recorded id that does not match the requested one is dropped, so a stale
 * entry cannot leak onto an unrelated attachment. Client render thread only.
 */
public final class AttachmentSkinContext {

    private record Pending(String attachmentId, String skinId) {
    }

    /** One field, so a read can never pair one stack's id with another's skin. */
    private static volatile Pending pending;

    private AttachmentSkinContext() {
    }

    public static void record(ItemStack stack, ResourceLocation attachmentId) {
        String skinId = TACZSkinHelper.getSkinId(stack);
        pending = skinId == null || attachmentId == null
                ? null
                : new Pending(attachmentId.toString(), skinId);
    }

    /** The skin recorded for {@code attachmentId}, or null. Always clears. */
    public static String consume(ResourceLocation attachmentId) {
        Pending current = pending;
        pending = null;
        if (current == null || attachmentId == null) return null;
        return attachmentId.toString().equals(current.attachmentId()) ? current.skinId() : null;
    }
}
