package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Attachment skin files to a patched index; cached, since it runs on every attachment render. */
public final class AttachmentSkinPatcher {

    private record Overrides(ResourceLocation texture, ResourceLocation icon, ResourceLocation model,
                             ResourceLocation lodModel, ResourceLocation lodTexture) {

        boolean isEmpty() {
            return texture == null && icon == null && model == null && lodModel == null && lodTexture == null;
        }
    }

    private record CacheEntry(Overrides overrides, ClientAttachmentIndex patched) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private AttachmentSkinPatcher() {
    }

    public static ClientAttachmentIndex patch(ClientAttachmentIndex base, String attachmentId, String skinId) {
        if (base == null || attachmentId == null || skinId == null || skinId.isBlank()) return null;

        Overrides overrides = resolve(base, attachmentId, skinId);
        if (overrides.isEmpty()) return null;

        String cacheKey = attachmentId + '\u0000' + skinId;
        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null && Objects.equals(existing.overrides(), overrides)) {
            return existing.patched();
        }

        ClientAttachmentIndex patched = ClientAttachmentIndexPatcher.withOverrides(base, overrides.texture(),
                overrides.icon(), overrides.model(), overrides.lodModel(), overrides.lodTexture());
        if (patched == null) {
            // Base not loaded yet: retry next frame, do not cache a miss.
            return null;
        }
        CACHE.put(cacheKey, new CacheEntry(overrides, patched));
        MCPSkins.LOGGER.info("[MCPSkins] Built attachment skin '{}' for '{}'.", skinId, attachmentId);
        return patched;
    }

    private static Overrides resolve(ClientAttachmentIndex base, String attachmentId, String skinId) {
        ResourceLocation baseTexture = ClientAttachmentIndexPatcher.getModelTexture(base);
        ResourceLocation texture = SkinAssetResolver.resolveTexture(MCPSkins.MOD_ID, attachmentId, skinId, baseTexture);

        ResourceLocation baseIcon = ClientAttachmentIndexPatcher.getSlotTexture(base);
        ResourceLocation icon = SkinAssetResolver.resolveIcon(MCPSkins.MOD_ID, attachmentId, skinId, baseIcon);

        ResourceLocation baseModel = ClientAttachmentIndexPatcher.getBaseModelLocation(base);
        ResourceLocation model = baseModel == null ? null : SkinAssetResolver.resolveModel(baseModel, skinId);

        ResourceLocation baseLodModel = ClientAttachmentIndexPatcher.getBaseLodModelLocation(base);
        ResourceLocation lodModel = baseLodModel == null ? null : SkinAssetResolver.resolveModel(baseLodModel, skinId);

        ResourceLocation baseLodTexture = ClientAttachmentIndexPatcher.getBaseLodTexture(base);
        ResourceLocation lodTexture = baseLodTexture == null ? null
                : SkinAssetResolver.resolveLodTexture(MCPSkins.MOD_ID, attachmentId, skinId, baseLodTexture);

        return new Overrides(
                Objects.equals(texture, baseTexture) ? null : texture,
                Objects.equals(icon, baseIcon) ? null : icon,
                model,
                lodModel,
                Objects.equals(lodTexture, baseLodTexture) ? null : lodTexture);
    }

    public static void clear() {
        CACHE.clear();
    }
}
