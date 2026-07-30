package org.minechestplate.mcpskins.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
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
 * Mixin into {@code TimelessAPI.getGunDisplay} that swaps in a skin's texture, icon,
 * HUD icon(s), and/or geometry (main and LOD) when the item stack has a
 * {@link SkinComponents#SKIN_ID} set. The weapon's GunId is never changed, only what
 * gets rendered for it.
 * <p>
 * Every override is resolved via {@link SkinAssetResolver} and is fully optional,
 * falling back to the base weapon's asset when the corresponding file is missing or
 * unsupported on this TACZ fork.
 * <p>
 * {@code stack} is passed into {@link GunModelPatcher#getOrCreate} purely so a freshly
 * built geo-model instance can have its animation state machine primed immediately - see
 * {@link GunModelPatcher}'s class javadoc for why that matters (it's what prevents
 * "detached hands" the first time a geo-model skin is equipped in first person).
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

        // baseLodModelLocation is null both when the fork isn't reflection-compatible and
        // when this weapon simply has no "lod" block - either way, nothing to override.
        ResourceLocation baseLodModelLocation = GunModelPatcher.getBaseLodModelLocation(base);
        ResourceLocation lodModel = baseLodModelLocation != null ? SkinAssetResolver.resolveModel(baseLodModelLocation, skinId) : null;
        boolean hasLodModel = lodModel != null;

        ResourceLocation baseLodTexture = GunModelPatcher.getBaseLodTexture(base);
        ResourceLocation lodTexture = baseLodTexture != null
                ? SkinAssetResolver.resolveLodTexture(MCPSkins.MOD_ID, baseGunId, skinId, baseLodTexture) : null;
        boolean hasLodTexture = lodTexture != null && !lodTexture.equals(baseLodTexture);

        ResourceLocation baseHud = GunDisplayInstancePatcher.getHud(base);
        ResourceLocation hud = SkinAssetResolver.resolveHud(MCPSkins.MOD_ID, baseGunId, skinId, baseHud);
        boolean hasHud = hud != null && !hud.equals(baseHud);

        ResourceLocation baseHudEmpty = GunDisplayInstancePatcher.getHudEmpty(base);
        ResourceLocation hudEmpty = SkinAssetResolver.resolveHudEmpty(MCPSkins.MOD_ID, baseGunId, skinId, baseHudEmpty);
        boolean hasHudEmpty = hudEmpty != null && !hudEmpty.equals(baseHudEmpty);

        if (!hasTexture && !hasModel && !hasLodModel && !hasLodTexture && !hasHud && !hasHudEmpty) {
            return; // nothing for this skin in any active resource pack
        }

        String cacheKey = baseGunId + '\u0000' + skinId;

        // Geometry is built first; texture/icon/HUD overrides layer on top of the
        // geo-patched instance (or the base instance, if geometry wasn't needed)
        GunDisplayInstance patchBase = base;
        if (hasModel || hasLodModel || hasLodTexture) {
            GunDisplayInstance geoInstance = GunModelPatcher.getOrCreate(cacheKey, base, stack,
                    hasModel ? model : null, hasLodModel ? lodModel : null, hasLodTexture ? lodTexture : null);
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
            // Geometry only - patchBase is already the final result
            if (patchBase != base) {
                cir.setReturnValue(Optional.of(patchBase));
            }
            return;
        }

        // Cached since this runs on essentially every render frame
        GunDisplayInstance patched = PatchedGunDisplayCache.getOrCreate(cacheKey, patchBase, textureOverride, iconOverride, hudOverride, hudEmptyOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        } else if (patchBase != base) {
            // Texture/icon/HUD patch isn't ready yet - show the correct geometry with the
            // base texture for now, the patch resolves on a later call
            cir.setReturnValue(Optional.of(patchBase));
        }
        // If both fall through, the original unskinned instance stands.
    }
}