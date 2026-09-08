package org.minechestplate.mcpskins.mixin;

import com.tacz.guns.api.item.nbt.AttachmentItemDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.client.render.AttachmentSkinContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes which stack an attachment id was read from, so {@link TimelessAPIMixin} knows whose
 * skin to apply when TACZ looks the index up by id alone.
 */
@Mixin(AttachmentItemDataAccessor.class)
public interface AttachmentIdAccessorMixin {

    @Inject(method = "getAttachmentId", at = @At("RETURN"), require = 0)
    default void mcpskins$recordSkin(ItemStack stack, CallbackInfoReturnable<ResourceLocation> cir) {
        AttachmentSkinContext.record(stack, cir.getReturnValue());
    }
}
