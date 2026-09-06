/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class SystemUiPolicyHookEntry extends XposedModule {
    private static final String TAG = "ColorLyric";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SPOTIFY = "com.spotify.music";
    private static final String QQ_MUSIC = "com.tencent.qqmusic";
    private static final String OPLUS_MEDIA_PREFIX = "com.oplus.systemui.media";
    private static final String LEGACY_SELECTOR =
            "com.oplus.systemui.media.controls.pipeline.MediaActionPrioritySelectorImpl";

    private final Set<String> hookedMethods = ConcurrentHashMap.newKeySet();
    private final Set<String> aliasLogged = ConcurrentHashMap.newKeySet();
    private final Set<XposedInterface.HookHandle> loadClassHandles =
            Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Boolean> discoveryGuard = new ThreadLocal<>();

    private volatile boolean entranceHooked;
    private volatile boolean enableHooked;
    private volatile boolean watcherInstalled;
    private volatile boolean initialized;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!SYSTEMUI.equals(param.getProcessName())) {
            detach();
            return;
        }
        info("SystemUI minimal lyric-policy hook loading; API=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName()) || initialized) return;
        synchronized (this) {
            if (initialized) return;
            initialized = true;

            boolean direct = tryHookKnownSelector(param.getClassLoader());
            if (!direct || !entranceHooked) {
                installClassLoadWatcher();
            }

            info("SystemUI policy hook ready entrance=" + entranceHooked
                    + " enable=" + enableHooked
                    + " watcher=" + watcherInstalled);
        }
    }

    private boolean tryHookKnownSelector(ClassLoader classLoader) {
        try {
            Class<?> selector = Class.forName(LEGACY_SELECTOR, false, classLoader);
            hookPolicyMethods(selector);
            return entranceHooked || enableHooked;
        } catch (Throwable error) {
            info("known selector unavailable; enabling discovery watcher: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private void installClassLoadWatcher() {
        if (watcherInstalled || entranceHooked) return;
        synchronized (this) {
            if (watcherInstalled || entranceHooked) return;
            watcherInstalled = true;

            for (Method method : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(method.getName())) continue;
                if (method.getParameterCount() < 1 || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedInterface.HookHandle handle = hook(method)
                            .setId("colorlyric-systemui-policy-discovery")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                if (!(result instanceof Class<?>)) return result;
                                if (Boolean.TRUE.equals(discoveryGuard.get())) return result;

                                Class<?> type = (Class<?>) result;
                                String name = type.getName();
                                if (name == null || !name.startsWith(OPLUS_MEDIA_PREFIX)) return result;

                                discoveryGuard.set(Boolean.TRUE);
                                try {
                                    hookPolicyMethods(type);
                                    if (entranceHooked) removeClassLoadWatcher();
                                } finally {
                                    discoveryGuard.remove();
                                }
                                return result;
                            });
                    loadClassHandles.add(handle);
                } catch (Throwable error) {
                    info("class discovery watcher method failed: "
                            + error.getClass().getSimpleName());
                }
            }

            if (loadClassHandles.isEmpty()) {
                watcherInstalled = false;
                info("OPlus media class discovery watcher unavailable");
            } else {
                info("OPlus media class discovery watcher installed");
            }
        }
    }

    private void removeClassLoadWatcher() {
        synchronized (this) {
            if (loadClassHandles.isEmpty()) return;
            for (XposedInterface.HookHandle handle : new HashSet<>(loadClassHandles)) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            loadClassHandles.clear();
            watcherInstalled = false;
            info("OPlus media class discovery watcher removed");
        }
    }

    private void hookPolicyMethods(Class<?> type) {
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
            if (!hookedMethods.add(key)) continue;

            try {
                method.setAccessible(true);
                hook(method)
                        .setId("colorlyric-systemui-" + name)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object arg = chain.getArg(0);
                            if (!SPOTIFY.equals(arg)) return chain.proceed();

                            Object[] args = chain.getArgs().toArray();
                            args[0] = QQ_MUSIC;
                            if (aliasLogged.add(name)) {
                                info("policy alias " + name + ": Spotify -> QQ Music");
                            }
                            return chain.proceed(args);
                        });

                if ("getLyricEntrance".equals(name)) entranceHooked = true;
                if ("getLyricEnable".equals(name)) enableHooked = true;
                info("hooked " + key);
            } catch (Throwable error) {
                hookedMethods.remove(key);
                info("failed to hook " + key + ": "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }

        if (entranceHooked) removeClassLoadWatcher();
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }
}
