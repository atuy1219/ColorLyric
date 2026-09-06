package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class ColorLyricModule extends XposedModule {
    private static final String TAG = "ColorLyric";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SPOTIFY = "com.spotify.music";
    private static final String QQ_MUSIC = "com.tencent.qqmusic";

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private volatile boolean spotifyLyricAvailable;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded into " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName())) return;
        if (!installed.compareAndSet(false, true)) return;

        log(Log.INFO, TAG, "SystemUI ready; resolving native OPlus lyric gates");
        OplusMediaGateResolver.Targets targets;
        try {
            targets = OplusMediaGateResolver.resolve(param.getClassLoader());
            log(Log.INFO, TAG, "Resolved OPlus media gates with DexKit");
        } catch (Throwable dexKitError) {
            log(Log.WARN, TAG, "DexKit resolution failed; trying legacy names", dexKitError);
            try {
                targets = OplusMediaGateResolver.resolveLegacy(param.getClassLoader());
                log(Log.INFO, TAG, "Resolved OPlus media gates with legacy names");
            } catch (Throwable legacyError) {
                log(Log.ERROR, TAG, "Unable to resolve OPlus native lyric gates", legacyError);
                return;
            }
        }

        hookAliasToQq(targets.lyricEntrance, "lyricEntrance");
        if (targets.lyricEnable != null) {
            hookAliasToQq(targets.lyricEnable, "lyricEnable");
        } else {
            log(Log.INFO, TAG, "getLyricEnable not present on this SystemUI build");
        }
        hookWhitelist(targets.legacyWhitelistGetter, "legacy RUS whitelist");
        hookWhitelist(targets.mediaRusWhitelistGetter, "MediaRusConfig whitelist");
        hookLyricLoader(targets.loadLyricInBg);
        hookSeedlingBundle(targets.mediaDataToBundle);
    }

    private void hookAliasToQq(Method method, String label) {
        if (method == null) return;
        try {
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        Object value = args.isEmpty() ? null : args.get(0);
                        if (!SPOTIFY.equals(value)) return chain.proceed();

                        args.set(0, QQ_MUSIC);
                        Object result = chain.proceed();
                        log(Log.INFO, TAG,
                                "Native gate " + label + ": Spotify -> QQ Music policy, value=" + result);
                        return result;
                    });
            log(Log.INFO, TAG, "Hooked " + label + ": " + method);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook " + label, t);
        }
    }

    private void hookWhitelist(Method method, String label) {
        if (method == null) return;
        try {
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof Collection<?>)) return original;

                        Collection<?> source = (Collection<?>) original;
                        if (source.contains(SPOTIFY)) return original;

                        Object patched;
                        if (original instanceof Set<?>) {
                            LinkedHashSet<Object> copy = new LinkedHashSet<>(source);
                            copy.add(SPOTIFY);
                            patched = copy;
                        } else {
                            ArrayList<Object> copy = new ArrayList<>(source);
                            copy.add(SPOTIFY);
                            patched = copy;
                        }
                        log(Log.INFO, TAG, "Added Spotify to " + label);
                        return patched;
                    });
            log(Log.INFO, TAG, "Hooked " + label + ": " + method);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook " + label, t);
        }
    }

    private void hookLyricLoader(Method method) {
        if (method == null) return;
        try {
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        String packageName = args.size() > 0 && args.get(0) instanceof String
                                ? (String) args.get(0) : "";
                        if (SPOTIFY.equals(packageName)) {
                            MediaMetadata metadata = args.size() > 1 && args.get(1) instanceof MediaMetadata
                                    ? (MediaMetadata) args.get(1) : null;
                            String lyricInfo = readLyricInfo(metadata);
                            spotifyLyricAvailable = lyricInfo != null && !lyricInfo.isBlank();
                            log(Log.INFO, TAG,
                                    "Spotify native lyric loader: lyricInfo="
                                            + (spotifyLyricAvailable ? "present" : "missing"));
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Hooked native lyricInfo loader: " + method);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook native lyric loader", t);
        }
    }

    private void hookSeedlingBundle(Method method) {
        if (method == null) return;
        try {
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        boolean spotifyInSeedling = args.size() > 0
                                && args.get(0) instanceof List<?>
                                && containsPackage((List<?>) args.get(0), SPOTIFY);

                        Object result = chain.proceed();
                        if (!(result instanceof Bundle)) return result;
                        Bundle bundle = (Bundle) result;

                        if (spotifyInSeedling || bundleContainsPackage(bundle, SPOTIFY)) {
                            boolean before = bundle.getBoolean("shouldShowLyric", false);
                            if (spotifyLyricAvailable || bundle.containsKey("currentLyric")) {
                                bundle.putBoolean("shouldShowLyric", true);
                            }
                            log(Log.INFO, TAG,
                                    "Seedling Spotify: shouldShowLyric " + before + " -> "
                                            + bundle.getBoolean("shouldShowLyric", false)
                                            + ", keys=" + bundle.keySet());
                        }
                        return bundle;
                    });
            log(Log.INFO, TAG, "Hooked Seedling media bundle mapper: " + method);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook Seedling bundle mapper", t);
        }
    }

    private static String readLyricInfo(MediaMetadata metadata) {
        if (metadata == null) return null;
        try {
            String direct = metadata.getString("lyricInfo");
            if (direct != null && !direct.isBlank()) return direct;
        } catch (Throwable ignored) {
        }
        try {
            Bundle extras = metadata.getDescription() == null
                    ? null : metadata.getDescription().getExtras();
            if (extras == null) return null;
            String value = extras.getString("lyricInfo");
            if (value == null || value.isBlank()) value = extras.getString("lyricinfo");
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsPackage(List<?> items, String packageName) {
        for (Object item : items) {
            if (item == null) continue;
            Object value = invokeNoArg(item, "getPackageName");
            if (packageName.equals(value)) return true;
            value = invokeNoArg(item, "getPackage");
            if (packageName.equals(value)) return true;
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean bundleContainsPackage(Bundle bundle, String packageName) {
        for (String key : new String[]{"packageName", "package", "pkg", "pkgName"}) {
            try {
                if (packageName.equals(bundle.getString(key))) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
