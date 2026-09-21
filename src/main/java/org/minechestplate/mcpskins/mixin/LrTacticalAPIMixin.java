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
 * Swaps a skin's texture and inventory icon into LesRaisins Tactical Equipments' display lookups,
 * the same way {@link TimelessAPIMixin} does for TACZ.
 * <p>
 * Targeted by name rather than by class: the addon is optional and is not on a public maven, so
 * there is nothing to compile against. Every injector is {@code require = 0}, which makes the
 * whole mixin inert when the mod is absent instead of failing to apply.
 * <p>
 * Its renderer resolves a display through {@code getDisplayId(stack)} before falling back to the
 * item id, so intercepting the API covers every place an item is drawn.
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

    /** Shared because all three display types expose the same texture and slotTexture fields. */
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
            return; // no art for this skin in any active pack
        }

        Object patched = LrDisplayPatcher.patch(base, baseId, skinId, textureOverride, iconOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        }
    }
}
