package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentLod;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Attachment counterpart of {@link GunDisplayInstancePatcher}; models load through TACZ's
 * {@code getOrLoadAttachmentModel} (no state machine). Reads fields, not getters, which would
 * force the lazy load. Must work on both TACZ ports.
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

    // False until TACZ has loaded the base model, so a copy never freezes a half-loaded state.
    public static boolean isReadyToPatch(ClientAttachmentIndex index) {
        Boolean loaded = read(index, "modelsLoaded", Boolean.class);
        if (loaded == null) return true; // field missing on this build: patch anyway
        if (!loaded) return false;
        Boolean failed = read(index, "modelsLoadFailed", Boolean.class);
        return failed == null || !failed;
    }

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

        if (texture != null || modelLocation != null || lodModelLocation != null || lodTexture != null) {
            detachExternalModels(copy);
        }
        return copy;
    }

    private static final String EXTERNAL_ASSETS_CLASS = "com.tacz.guns.client.resource.ExternalModelAssets";
    private static final String LOAD_TASK_CLASS = "com.tacz.guns.client.resource.ClientAssetLoadTask";
    private static volatile boolean externalSearched = false;
    private static volatile Constructor<?> externalAssetsConstructor;
    private static volatile Method externalAssetsAdd;

    // Official port only: its baked external meshes have no room for a skin, so empty them and let the
    // per-frame renderer read this copy.
    private static void detachExternalModels(ClientAttachmentIndex copy) {
        if (!externalSearched) {
            try {
                Class<?> assetsClass = Class.forName(EXTERNAL_ASSETS_CLASS);
                Method add = assetsClass.getMethod("add", Supplier.class, Supplier.class, Consumer.class);
                Constructor<?> constructor = assetsClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                externalAssetsAdd = add;
                externalAssetsConstructor = constructor;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            externalSearched = true;
        }
        if (externalAssetsConstructor == null) return;

        try {
            for (Field field : ClientAttachmentIndex.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String type = field.getType().getName();
                Object replacement;
                if (type.equals(EXTERNAL_ASSETS_CLASS)) {
                    replacement = externalAssetsConstructor.newInstance();
                } else if (type.equals(LOAD_TASK_CLASS)) {
                    // A never-yielding task from a throwaway owner, so the copy's list stays empty, not pending.
                    Supplier<Object> none = () -> null;
                    Consumer<Throwable> ignore = failure -> { };
                    replacement = externalAssetsAdd.invoke(externalAssetsConstructor.newInstance(), none, none, ignore);
                } else {
                    continue;
                }
                field.setAccessible(true);
                field.set(copy, replacement);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("externalModels");
        }
    }

    // Scope and sight flags live on the model, so a replacement must inherit them.
    private static BedrockAttachmentModel loadModel(ResourceLocation location, ClientAttachmentIndex base) {
        BedrockAttachmentModel model = ClientAttachmentIndex.getOrLoadAttachmentModel(location);
        if (model == null) return null;
        model.setIsScope(base.isScope());
        model.setIsSight(base.isSight());
        copyScopeLens(base, model);
        return model;
    }

    // Official port only: lens config lives on the model too. Both stay null on MUKSC.
    private static volatile boolean scopeLensSearched = false;
    private static volatile Method getScopeLens; // ClientAttachmentIndex
    private static volatile Method setScopeLens; // BedrockAttachmentModel

    private static void copyScopeLens(ClientAttachmentIndex base, BedrockAttachmentModel model) {
        if (!scopeLensSearched) {
            try {
                Method getter = ClientAttachmentIndex.class.getMethod("getScopeLens");
                setScopeLens = BedrockAttachmentModel.class.getMethod("setScopeLens", getter.getReturnType());
                getScopeLens = getter;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            scopeLensSearched = true;
        }
        if (getScopeLens == null || setScopeLens == null) return;
        try {
            Object lens = getScopeLens.invoke(base);
            if (lens != null) setScopeLens.invoke(model, lens);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("scopeLens");
        }
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
