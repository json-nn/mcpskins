package org.minechestplate.mcpskins.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.AttachmentSkinContext;
import org.minechestplate.mcpskins.client.render.AttachmentSkinPatcher;
import org.minechestplate.mcpskins.client.render.GunDisplayInstancePatcher;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Swaps a skin's texture, icon, HUD icons, geometry and animations into TACZ's display lookups for
 * stacks with {@link SkinComponents#SKIN_ID}. GunId never changes. Runs every frame; see
 * docs/ai/tacz-integration.md.
 */
@Mixin(TimelessAPI.class)
public class TimelessAPIMixin {

    @Inject(
            method = "getGunDisplay(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void mcpskins$applySkinOverride(ItemStack stack, CallbackInfoReturnable<Optional<GunDisplayInstance>> cir) {
        Optional<GunDisplayInstance> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;

        String skinId = stack.get(SkinComponents.SKIN_ID.get());
        if (skinId == null || skinId.isBlank()) return;

        String baseGunId = TACZSkinHelper.getGunId(stack);
        if (baseGunId == null) return;

        GunDisplayInstance base = original.get();
        ResourceLocation baseTexture = GunDisplayInstancePatcher.getTexture(base);

        ResourceLocation texture = SkinAssetResolver.resolveTexture(MCPSkins.MOD_ID, baseGunId, skinId, baseTexture);
        boolean hasTexture = texture != null && !texture.equals(baseTexture);

        ResourceLocation baseModelLocation = GunModelPatcher.getBaseModelLocation(base);
        ResourceLocation model = baseModelLocation != null ? SkinAssetResolver.resolveModel(baseModelLocation, skinId) : null;
        boolean hasModel = model != null;

        ResourceLocation baseLodModelLocation = GunModelPatcher.getBaseLodModelLocation(base);
        ResourceLocation lodModel = baseLodModelLocation != null ? SkinAssetResolver.resolveModel(baseLodModelLocation, skinId) : null;
        boolean hasLodModel = lodModel != null;

        ResourceLocation baseLodTexture = GunModelPatcher.getBaseLodTexture(base);
        ResourceLocation lodTexture = baseLodTexture != null
                ? SkinAssetResolver.resolveLodTexture(MCPSkins.MOD_ID, baseGunId, skinId, baseLodTexture) : null;
        boolean hasLodTexture = lodTexture != null && !lodTexture.equals(baseLodTexture);

        // A gun with no animation file of its own still takes a skin's.
        ResourceLocation animation = GunModelPatcher.isAnimationOverrideSupported(base)
                ? SkinAssetResolver.resolveAnimation(MCPSkins.MOD_ID, baseGunId, skinId,
                        GunModelPatcher.getBaseAnimationLocation(base))
                : null;
        boolean hasAnimation = animation != null;

        ResourceLocation baseHud = GunDisplayInstancePatcher.getHud(base);
        ResourceLocation hud = SkinAssetResolver.resolveHud(MCPSkins.MOD_ID, baseGunId, skinId, baseHud);
        boolean hasHud = hud != null && !hud.equals(baseHud);

        ResourceLocation baseHudEmpty = GunDisplayInstancePatcher.getHudEmpty(base);
        ResourceLocation hudEmpty = SkinAssetResolver.resolveHudEmpty(MCPSkins.MOD_ID, baseGunId, skinId, baseHudEmpty);
        boolean hasHudEmpty = hudEmpty != null && !hudEmpty.equals(baseHudEmpty);

        if (!hasTexture && !hasModel && !hasLodModel && !hasLodTexture && !hasAnimation && !hasHud && !hasHudEmpty) {
            return;
        }

        String cacheKey = baseGunId + '\u0000' + skinId;

        // Geometry and animations first; texture, icon and HUD layer on top of that.
        GunDisplayInstance patchBase = base;
        if (hasModel || hasLodModel || hasLodTexture || hasAnimation) {
            GunDisplayInstance geoInstance = GunModelPatcher.getOrCreate(cacheKey, base, stack,
                    hasModel ? model : null, hasLodModel ? lodModel : null, hasLodTexture ? lodTexture : null,
                    animation);
            if (geoInstance != null) {
                patchBase = geoInstance;
            }
        }

        ResourceLocation baseIcon = GunDisplayInstancePatcher.getIcon(base);
        ResourceLocation icon = SkinAssetResolver.resolveIcon(MCPSkins.MOD_ID, baseGunId, skinId, baseIcon);
        ResourceLocation iconOverride = (icon != null && !icon.equals(baseIcon)) ? icon : null;
        ResourceLocation textureOverride = hasTexture ? texture : null;
        ResourceLocation hudOverride = hasHud ? hud : null;
        ResourceLocation hudEmptyOverride = hasHudEmpty ? hudEmpty : null;

        if (textureOverride == null && iconOverride == null && hudOverride == null && hudEmptyOverride == null) {
            if (patchBase != base) {
                cir.setReturnValue(Optional.of(patchBase));
            }
            return;
        }

        GunDisplayInstance patched = PatchedGunDisplayCache.getOrCreate(cacheKey, patchBase,
                patchBase != base ? patchBase : null, textureOverride, iconOverride, hudOverride, hudEmptyOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        } else if (patchBase != base) {
            // Texture patch not ready: show the new geometry on the base texture meanwhile.
            cir.setReturnValue(Optional.of(patchBase));
        }
    }

    // Keyed by id, so the skin arrives through AttachmentSkinContext; always consumed, hit or miss.
    @Inject(
            method = "getClientAttachmentIndex",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private static void mcpskins$applyAttachmentSkin(ResourceLocation attachmentId,
                                                     CallbackInfoReturnable<Optional<ClientAttachmentIndex>> cir) {
        String skinId = AttachmentSkinContext.consume(attachmentId);
        Optional<ClientAttachmentIndex> original = cir.getReturnValue();
        if (skinId == null || original == null || original.isEmpty()) return;

        ClientAttachmentIndex patched = AttachmentSkinPatcher.patch(original.get(), attachmentId.toString(), skinId);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        }
    }
}
