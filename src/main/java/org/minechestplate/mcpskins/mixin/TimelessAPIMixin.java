package org.minechestplate.mcpskins.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.render.GunDisplayInstancePatcher;
import org.minechestplate.mcpskins.skin.render.GunModelPatcher;
import org.minechestplate.mcpskins.skin.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.skin.render.SkinAssetResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Mixin into {@code TimelessAPI.getGunDisplay} that swaps in a skin's texture, icon,
 * and/or geometry when the item stack has a {@link SkinComponents#SKIN_ID} set. The
 * weapon's GunId is never changed - only what gets rendered for it.
 * <p>
 * Texture and icon overrides are resolved via {@link SkinAssetResolver} from files at
 * {@code textures/skins/<baseGunId>/<skinId>.png} (and an optional {@code _icon.png}).
 * A full geometry replacement is attempted via {@link GunModelPatcher} if a matching
 * geo-model file is found next to the weapon's real model. Both the icon and geometry
 * overrides are fully optional and silently fall back to a texture-only skin when the
 * corresponding file is missing or unsupported.
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

        // Full geo-model replacement: namespace/folder come from this weapon's actual base
        // model rather than being guessed. If the fork's internals aren't reflection-
        // compatible, baseModelLocation is null and the skin stays texture-only.
        ResourceLocation baseModelLocation = GunModelPatcher.getBaseModelLocation(base);
        ResourceLocation model = baseModelLocation != null ? SkinAssetResolver.resolveModel(baseModelLocation, skinId) : null;
        boolean hasModel = model != null;

        if (!hasTexture && !hasModel) {
            return; // no texture or model for this skin in any active resource pack
        }

        String cacheKey = baseGunId + '\u0000' + skinId;

        // Geometry is built first; texture/icon overrides then layer on top of the
        // geo-patched instance (or the base instance, if geometry patching failed).
        GunDisplayInstance patchBase = base;
        if (hasModel) {
            GunDisplayInstance geoInstance = GunModelPatcher.getOrCreate(cacheKey, base, model);
            if (geoInstance != null) {
                patchBase = geoInstance;
            }
        }

        // Optional 2D icon override, if a "<skinId>_icon.png" exists next to the skin texture
        ResourceLocation baseIcon = GunDisplayInstancePatcher.getIcon(base);
        ResourceLocation icon = SkinAssetResolver.resolveIcon(MCPSkins.MOD_ID, baseGunId, skinId, baseIcon);
        ResourceLocation iconOverride = (icon != null && !icon.equals(baseIcon)) ? icon : null;
        ResourceLocation textureOverride = hasTexture ? texture : null;

        if (textureOverride == null && iconOverride == null) {
            // Geometry only, no texture/icon changes - patchBase is already the final result
            if (patchBase != base) {
                cir.setReturnValue(Optional.of(patchBase));
            }
            return;
        }

        // Cached rather than calling GunDisplayInstancePatcher.withOverrides directly, since
        // this runs on essentially every render frame - without caching it would allocate a
        // new patched instance every call for the same (weapon, skin) pair.
        GunDisplayInstance patched = PatchedGunDisplayCache.getOrCreate(cacheKey, patchBase, textureOverride, iconOverride);
        if (patched != null) {
            cir.setReturnValue(Optional.of(patched));
        } else if (patchBase != base) {
            // Texture/icon patch isn't ready yet (model still loading lazily) - show the
            // correct geometry with the base texture for now; the patch resolves on a
            // subsequent call.
            cir.setReturnValue(Optional.of(patchBase));
        }
        // If both patched and patchBase fall through, the original unskinned instance stands.
    }
}