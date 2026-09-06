package com.atuy.colorlyric;

import android.os.Bundle;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;

final class OplusMediaGateResolver {
    private static volatile boolean dexKitLoaded;

    private OplusMediaGateResolver() {}

    static Targets resolve(ClassLoader classLoader) throws Exception {
        ensureDexKitLoaded();
        try (DexKitBridge bridge = DexKitBridge.create(classLoader, true)) {
            Class<?> selector = findSingleClass(
                    bridge,
                    classLoader,
                    "com.oplus.systemui.media",
                    new String[]{
                            "not rule, use Actions",
                            "oplusActionConfig=",
                            "Test MediaAction, but not rule, use notification Actions"
                    }
            );

            Method lyricEntrance = findNamedIntStringMethod(selector, "getLyricEntrance");
            Method lyricEnable = findOptionalNamedIntStringMethod(selector, "getLyricEnable");

            Class<?> lyricLoader = findSingleClass(
                    bridge,
                    classLoader,
                    "com.oplus.systemui.media",
                    new String[]{
                            "loadLyricInBg reason: lyric is avilable, lyric= ",
                            "loadLyricInBg reason: song changed, lyric= ",
                            "Failed to parse lyric data: "
                    }
            );
            Method loadLyricInBg = findLyricLoaderMethod(lyricLoader);

            Class<?> seedlingMapper = findSingleClass(
                    bridge,
                    classLoader,
                    "com.oplus.systemui.seedlingservice",
                    new String[]{"mediaId", "currentLyric", "lastPositionUpdateTime", "shouldShowLyric"}
            );
            Method mediaDataToBundle = findSeedlingBundleMethod(seedlingMapper);

            Method legacyWhitelist = null;
            try {
                Class<?> rusManager = findSingleClassAny(
                        bridge,
                        classLoader,
                        "com.oplus.systemui.media",
                        new String[]{
                                "parseSaveXmlValue whiteList: ",
                                "getRusWhiteList: cache is empty",
                                "app_systemui_oplus_media_controller_config.xml"
                        },
                        new String[]{
                                "parseSaveXmlValue whiteList: ",
                                "parseSaveXmlValue pkgRuleMap: ",
                                "applyConfig version="
                        }
                );
                legacyWhitelist = findOptionalCollectionGetter(rusManager, "getRusWhiteList");
            } catch (Throwable ignored) {
            }

            Method mediaRusWhitelist = null;
            try {
                Class<?> mediaRusConfig = classLoader.loadClass(
                        "com.oplus.systemui.media.seedling.rus.MediaRusConfig"
                );
                mediaRusWhitelist = findOptionalCollectionGetter(mediaRusConfig, "getWhiteList");
            } catch (Throwable ignored) {
            }

            return new Targets(
                    lyricEntrance,
                    lyricEnable,
                    loadLyricInBg,
                    mediaDataToBundle,
                    legacyWhitelist,
                    mediaRusWhitelist
            );
        }
    }

    static Targets resolveLegacy(ClassLoader classLoader) throws Exception {
        Class<?> selector = classLoader.loadClass(
                "com.oplus.systemui.media.controls.pipeline.MediaActionPrioritySelectorImpl"
        );
        Method lyricEntrance = findNamedIntStringMethod(selector, "getLyricEntrance");
        Method lyricEnable = findOptionalNamedIntStringMethod(selector, "getLyricEnable");

        Class<?> manager = classLoader.loadClass(
                "com.oplus.systemui.media.controls.pipeline.OplusMediaDataManagerExImpl"
        );
        Method loadLyric = null;
        for (Method method : manager.getDeclaredMethods()) {
            if ("loadLyricInBg".equals(method.getName())) {
                method.setAccessible(true);
                loadLyric = method;
                break;
            }
        }

        Class<?> seedling = classLoader.loadClass(
                "com.oplus.systemui.seedlingservice.utils.SeedlingMediaDataHandleUtils"
        );
        Method mediaDataToBundle = findSeedlingBundleMethod(seedling);

        Method legacyWhitelist = null;
        try {
            Class<?> rus = classLoader.loadClass(
                    "com.oplus.systemui.media.seedling.rus.OplusMediaRusUpdateManager"
            );
            legacyWhitelist = findOptionalCollectionGetter(rus, "getRusWhiteList");
        } catch (Throwable ignored) {
        }

        Method newWhitelist = null;
        try {
            Class<?> config = classLoader.loadClass(
                    "com.oplus.systemui.media.seedling.rus.MediaRusConfig"
            );
            newWhitelist = findOptionalCollectionGetter(config, "getWhiteList");
        } catch (Throwable ignored) {
        }

        return new Targets(
                lyricEntrance,
                lyricEnable,
                loadLyric,
                mediaDataToBundle,
                legacyWhitelist,
                newWhitelist
        );
    }

    private static Class<?> findSingleClass(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String packageName,
            String[] anchors
    ) throws Exception {
        ClassDataList classes = bridge.findClass(FindClass.create()
                .searchPackages(packageName)
                .matcher(ClassMatcher.create().usingEqStrings(anchors)));
        if (classes.size() != 1) {
            throw new IllegalStateException(
                    "Expected one class in " + packageName + ", got " + classes.size()
            );
        }
        ClassData data = classes.get(0);
        return data.getInstance(classLoader);
    }

    private static Class<?> findSingleClassAny(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String packageName,
            String[] first,
            String[] second
    ) throws Exception {
        ClassMatcher matcher = ClassMatcher.create().anyOf(
                ClassMatcher.create().usingEqStrings(first),
                ClassMatcher.create().usingEqStrings(second)
        );
        ClassDataList classes = bridge.findClass(FindClass.create()
                .searchPackages(packageName)
                .matcher(matcher));
        if (classes.size() != 1) {
            throw new IllegalStateException(
                    "Expected one RUS class in " + packageName + ", got " + classes.size()
            );
        }
        return classes.get(0).getInstance(classLoader);
    }

    private static Method findNamedIntStringMethod(Class<?> owner, String name)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, String.class);
        if (method.getReturnType() != int.class || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + '#' + name + " has unexpected shape");
        }
        method.setAccessible(true);
        return method;
    }

    private static Method findOptionalNamedIntStringMethod(Class<?> owner, String name) {
        try {
            return findNamedIntStringMethod(owner, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findLyricLoaderMethod(Class<?> owner) throws NoSuchMethodException {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    && p.length == 6
                    && p[0] == String.class
                    && "android.media.MediaMetadata".equals(p[1].getName())) {
                if (match != null) throw new NoSuchMethodException("Ambiguous lyric loader");
                match = method;
            }
        }
        if (match == null) throw new NoSuchMethodException("No lyric loader");
        match.setAccessible(true);
        return match;
    }

    private static Method findSeedlingBundleMethod(Class<?> owner) throws NoSuchMethodException {
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == Bundle.class
                    && p.length == 3
                    && List.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class
                    && p[2] == boolean.class) {
                if (match != null) throw new NoSuchMethodException("Ambiguous Seedling mapper");
                match = method;
            }
        }
        if (match == null) throw new NoSuchMethodException("No Seedling mapper");
        match.setAccessible(true);
        return match;
    }

    private static Method findOptionalCollectionGetter(Class<?> owner, String name) {
        for (Method method : owner.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterCount() == 0
                    && Collection.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void ensureDexKitLoaded() {
        if (dexKitLoaded) return;
        synchronized (OplusMediaGateResolver.class) {
            if (dexKitLoaded) return;
            System.loadLibrary("dexkit");
            dexKitLoaded = true;
        }
    }

    static final class Targets {
        final Method lyricEntrance;
        final Method lyricEnable;
        final Method loadLyricInBg;
        final Method mediaDataToBundle;
        final Method legacyWhitelistGetter;
        final Method mediaRusWhitelistGetter;

        Targets(
                Method lyricEntrance,
                Method lyricEnable,
                Method loadLyricInBg,
                Method mediaDataToBundle,
                Method legacyWhitelistGetter,
                Method mediaRusWhitelistGetter
        ) {
            this.lyricEntrance = lyricEntrance;
            this.lyricEnable = lyricEnable;
            this.loadLyricInBg = loadLyricInBg;
            this.mediaDataToBundle = mediaDataToBundle;
            this.legacyWhitelistGetter = legacyWhitelistGetter;
            this.mediaRusWhitelistGetter = mediaRusWhitelistGetter;
        }
    }
}
