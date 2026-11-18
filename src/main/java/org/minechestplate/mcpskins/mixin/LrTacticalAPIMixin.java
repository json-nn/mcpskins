package org.minechestplate.mcpskins.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.LrDisplayPatcher;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Texture and icon skins for LesRaisins Tactical Equipments, like {@link TimelessAPIMixin} for TACZ.
 * Targeted by name (optional mod, no public maven); keep every injector {@code require = 0} so the
 * mixin is inert without it. Its renderers all resolve displays through this API.
 */
@Mixin(targets = "me.xjqsh.lrtactical.api.LrTacticalAPI", remap = false)
public class LrTacticalAPIMixin {

    @Inject(
            method = "getMeleeDisplay(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true, require = 0
    )
    private static void mcpskins$skinMelee(ItemStack stack, CallbackInfoReturnable<Optional<Object>> cir) {
        mcpskins$apply(stack, cir);
    }

    @Inject(
            method = "getThrowableDisplay(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true, require = 0
    )
    private static void mcpskins$skinThrowable(ItemStack stack, CallbackInfoReturnable<Optional<Object>> cir) {
        mcpskins$apply(stack, cir);
    }

    @Inject(
            method = "getConsumableDisplay(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true, require = 0
    )
    private static void mcpskins$skinConsumable(ItemStack stack, CallbackInfoReturnable<Optional<Object>> cir) {
        mcpskins$apply(stack, cir);
    }

    // All three display types share the texture and slotTexture fields.
    private static void mcpskins$apply(ItemStack stack, CallbackInfoReturnable<Optional<Object>> cir) {
        Optional<Object> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        String skinId = stack.get(SkinComponents.SKIN_ID.get());
        if (skinId == null || skinId.isBlank()) return;

        String baseId = TACZSkinHelper.getTaczId(stack);
        if (baseId == null) return;

        Object base = original.get();

        ResourceLocation baseTexture = LrDisplayPatcher.getTexture(base);
        ResourceLocation texture = SkinAssetResolver.resolveTexture(MCPSkins.MOD_ID, baseId, skinId, baseTexture);
        ResourceLocation textureOverride = (texture != null && !texture.equals(baseTexture)) ? texture : null;

        ResourceLocation baseIcon = LrDisplayPatcher.getSlotTexture(base);
        ResourceLocation icon = SkinAssetResolver.resolveIcon(MCPSkins.MOD_ID, baseId, skinId, baseIcon);
        ResourceLocation iconOverride = (icon != null && !icon.equals(baseIcon)) ? icon : null;

        if (textureOverride == null && iconOverride == null) {
            return;
        }

        Object patched = LrDisplayPatcher.patch(base, baseId, skinId, textureOverride, iconOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        }
    }
}
