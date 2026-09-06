/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Minimal OPlus SystemUI compatibility hook.
 *
 * <p>This class does not touch lyric parsing, rendering, LyricsRecyclerView, MediaData or
 * notifications. It only makes OPlus lyric package-policy lookups evaluate Spotify with the same
 * package policy as QQ Music, which is a stock-supported player on the target ROM.</p>
 */
public final class SystemUiPolicyHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorLyric";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SPOTIFY = "com.spotify.music";
    private static final String QQ_MUSIC = "com.tencent.qqmusic";
    private static final String OPLUS_MEDIA_PREFIX = "com.oplus.systemui.media";
    private static final String LEGACY_SELECTOR =
            "com.oplus.systemui.media.controls.pipeline.MediaActionPrioritySelectorImpl";

    private static final Set<String> HOOKED_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<XC_MethodHook.Unhook> LOAD_CLASS_UNHOOKS =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean entranceHooked;
    private static volatile boolean enableHooked;
    private static volatile boolean watcherInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName) || !SYSTEMUI.equals(lpparam.processName)) return;

        log("SystemUI minimal lyric-policy hook loading");

        boolean direct = tryHookKnownSelector(lpparam.classLoader);
        if (!direct || !entranceHooked) {
            installClassLoadWatcher();
        }

        log("SystemUI policy hook ready entrance=" + entranceHooked
                + " enable=" + enableHooked
                + " watcher=" + watcherInstalled);
    }

    private static boolean tryHookKnownSelector(ClassLoader classLoader) {
        try {
            Class<?> selector = Class.forName(LEGACY_SELECTOR, false, classLoader);
            hookPolicyMethods(selector);
            return entranceHooked || enableHooked;
        } catch (Throwable error) {
            log("known selector unavailable; enabling discovery watcher: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private static void installClassLoadWatcher() {
        if (watcherInstalled) return;
        synchronized (SystemUiPolicyHookEntry.class) {
            if (watcherInstalled) return;
            watcherInstalled = true;

            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Class<?>)) return;
                    Class<?> type = (Class<?>) result;
                    String name = type.getName();
                    if (name == null || !name.startsWith(OPLUS_MEDIA_PREFIX)) return;

                    hookPolicyMethods(type);
                    if (entranceHooked && enableHooked) {
                        removeClassLoadWatcher();
                    }
                }
            };

            try {
                LOAD_CLASS_UNHOOKS.addAll(XposedBridge.hookAllMethods(
                        ClassLoader.class, "loadClass", callback));
                log("OPlus media class discovery watcher installed");
            } catch (Throwable error) {
                log("class discovery watcher failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }

    private static void removeClassLoadWatcher() {
        synchronized (SystemUiPolicyHookEntry.class) {
            if (LOAD_CLASS_UNHOOKS.isEmpty()) return;
            for (XC_MethodHook.Unhook unhook : new HashSet<>(LOAD_CLASS_UNHOOKS)) {
                try {
                    unhook.unhook();
                } catch (Throwable ignored) {
                }
            }
            LOAD_CLASS_UNHOOKS.clear();
            log("OPlus media class discovery watcher removed");
        }
    }

    private static void hookPolicyMethods(Class<?> type) {
        if (type == null) return;
        Method[] methods;
        try {
            methods = type.getDeclaredMethods();
        } catch (Throwable ignored) {
            return;
        }

        for (Method method : methods) {
            String name = method.getName();
            if (!"getLyricEntrance".equals(name) && !"getLyricEnable".equals(name)) continue;
            if (method.getReturnType() != int.class || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != String.class) continue;

            String key = type.getName() + "#" + name;
            if (!HOOKED_METHODS.add(key)) continue;

            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        if (!SPOTIFY.equals(param.args[0])) return;
                        param.args[0] = QQ_MUSIC;
                        log("policy alias " + name + ": Spotify -> QQ Music");
                    }
                });

                if ("getLyricEntrance".equals(name)) entranceHooked = true;
                if ("getLyricEnable".equals(name)) enableHooked = true;
                log("hooked " + key);
            } catch (Throwable error) {
                HOOKED_METHODS.remove(key);
                log("failed to hook " + key + ": "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
