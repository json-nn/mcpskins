package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentLod;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a copy of a {@link ClientAttachmentIndex} with the skin's UV texture, inventory icon
 * and geometry written in, the attachment-side counterpart of {@link GunDisplayInstancePatcher}.
 * <p>
 * Simpler than the gun path: an attachment model carries no animation state machine, so the
 * replacement can be loaded through TACZ's own {@code getOrLoadAttachmentModel} and dropped
 * into a field instead of re-running a constructor.
 * <p>
 * Reads go through the fields rather than the getters, which force the lazy model load as a
 * side effect. Any single override that fails leaves the rest applied.
 */
public final class ClientAttachmentIndexPatcher {

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static volatile boolean fieldWarningLogged = false;
    private static volatile Unsafe cachedUnsafe;

    private ClientAttachmentIndexPatcher() {
    }

    public static ResourceLocation getModelTexture(ClientAttachmentIndex index) {
        return read(index, "modelTexture", ResourceLocation.class);
    }

    public static ResourceLocation getSlotTexture(ClientAttachmentIndex index) {
        return read(index, "slotTexture", ResourceLocation.class);
    }

    /** Collapsed model location from the attachment's display config, or null. */
    public static ResourceLocation getBaseModelLocation(ClientAttachmentIndex index) {
        AttachmentDisplay display = read(index, "display", AttachmentDisplay.class);
        return display == null ? null : display.getModel();
    }

    public static ResourceLocation getBaseLodModelLocation(ClientAttachmentIndex index) {
        AttachmentLod lod = baseLod(index);
        return lod == null ? null : lod.getModelLocation();
    }

    public static ResourceLocation getBaseLodTexture(ClientAttachmentIndex index) {
        AttachmentLod lod = baseLod(index);
        return lod == null ? null : lod.getModelTexture();
    }

    private static AttachmentLod baseLod(ClientAttachmentIndex index) {
        AttachmentDisplay display = read(index, "display", AttachmentDisplay.class);
        return display == null ? null : display.getAttachmentLod();
    }

    /** False until TACZ has loaded the base model, so a copy can't freeze a half-loaded state. */
    public static boolean isReadyToPatch(ClientAttachmentIndex index) {
        Boolean loaded = read(index, "modelsLoaded", Boolean.class);
        if (loaded == null) return true; // field gone on this fork, patch anyway
        if (!loaded) return false;
        Boolean failed = read(index, "modelsLoadFailed", Boolean.class);
        return failed == null || !failed;
    }

    /**
     * @return a patched copy, or null if the base isn't loaded yet or the copy failed
     */
    public static ClientAttachmentIndex withOverrides(ClientAttachmentIndex index, ResourceLocation texture,
                                                      ResourceLocation icon, ResourceLocation modelLocation,
                                                      ResourceLocation lodModelLocation, ResourceLocation lodTexture) {
        if (index == null || !isReadyToPatch(index)) return null;

        ClientAttachmentIndex copy = shallowCopy(index);
        if (copy == null) return null;

        if (texture != null) write(copy, "modelTexture", texture);
        if (icon != null) write(copy, "slotTexture", icon);

        if (modelLocation != null) {
            BedrockAttachmentModel model = loadModel(modelLocation, index);
            if (model != null) write(copy, "attachmentModel", model);
        }

        if (lodModelLocation != null || lodTexture != null) {
            Pair<BedrockAttachmentModel, ResourceLocation> baseLod = index.getLodModel();
            BedrockAttachmentModel lodModel = lodModelLocation != null ? loadModel(lodModelLocation, index) : null;
            if (lodModel == null && baseLod != null) lodModel = baseLod.getLeft();
            ResourceLocation resolvedLodTexture = lodTexture != null ? lodTexture
                    : baseLod != null ? baseLod.getRight() : null;
            if (lodModel != null && resolvedLodTexture != null) {
                write(copy, "lodModel", Pair.of(lodModel, resolvedLodTexture));
            }
        }
        return copy;
    }

    /**
     * Scope and sight flags live on the model, not the index, so a replacement has to be told
     * what it is or an aimed-down scope stops behaving like one.
     */
    private static BedrockAttachmentModel loadModel(ResourceLocation location, ClientAttachmentIndex base) {
        BedrockAttachmentModel model = ClientAttachmentIndex.getOrLoadAttachmentModel(location);
        if (model == null) return null;
        model.setIsScope(base.isScope());
        model.setIsSight(base.isSight());
        return model;
    }

    private static <T> T read(ClientAttachmentIndex index, String name, Class<T> type) {
        if (index == null) return null;
        Field field = field(name);
        if (field == null) return null;
        try {
            Object value = field.get(index);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void write(ClientAttachmentIndex index, String name, Object value) {
        Field field = field(name);
        if (field == null) return;
        try {
            field.set(index, value);
        } catch (IllegalAccessException e) {
            warnOnce(name);
        }
    }

    private static Field field(String name) {
        Field cached = FIELDS.get(name);
        if (cached != null) return cached;
        try {
            Field found = ClientAttachmentIndex.class.getDeclaredField(name);
            found.setAccessible(true);
            FIELDS.put(name, found);
            return found;
        } catch (NoSuchFieldException e) {
            warnOnce(name);
            return null;
        }
    }

    private static void warnOnce(String name) {
        if (fieldWarningLogged) return;
        fieldWarningLogged = true;
        MCPSkins.LOGGER.warn("[MCPSkins] ClientAttachmentIndex field '{}' is missing or unwritable on this "
                + "TACZ build; that attachment skin override stays the base asset.", name);
    }

    private static ClientAttachmentIndex shallowCopy(ClientAttachmentIndex index) {
        try {
            ClientAttachmentIndex copy = (ClientAttachmentIndex) unsafe().allocateInstance(ClientAttachmentIndex.class);
            for (Class<?> current = ClientAttachmentIndex.class; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    field.set(copy, field.get(index));
                }
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("[MCPSkins] Could not copy ClientAttachmentIndex; attachment skins are off "
                    + "for this session, everything else keeps working.", e);
            return null;
        }
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Unsafe local = cachedUnsafe;
        if (local != null) return local;
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        local = (Unsafe) field.get(null);
        cachedUnsafe = local;
        return local;
    }
}
