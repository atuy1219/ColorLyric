/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Compatibility layer for Spotify's frequently renamed networking classes.
 *
 * <p>The main Spotify hook intentionally owns lyric fetching and publication. This module only keeps
 * SpotifyHeaderStore fed across R8 name changes. When headers become ready after the current track
 * metadata was already published, it replays that metadata once so SpotifyHookEntry can immediately
 * start its normal fetch path.</p>
 */
public final class SpotifyHeaderCompatEntry extends XposedModule {
    private static final String TAG = "ColorLyric";
    private static final String SPOTIFY = "com.spotify.music";

    private static final String[] HEADER_CONTAINER_FAST_PATHS = {
            // Spotify 9.1.82.2160
            "p.ob20",
            // Previous Spotify build used by ColorLyric
            "p.ot10",
            "okhttp3.Headers"
    };

    private static final String[] ADD_HEADER_FAST_PATHS = {
            // Spotify 9.1.82.2160 Cronet request builder
            "p.ns91",
            // Previous Spotify build used by ColorLyric
            "p.aj81",
            "org.chromium.net.UrlRequest$Builder"
    };

    private final ThreadLocal<Boolean> discoveryGuard = new ThreadLocal<>();
    private final ThreadLocal<Boolean> replayGuard = new ThreadLocal<>();
    private final Set<String> hookedContainerClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> hookedHeaderMethods = ConcurrentHashMap.newKeySet();
    private final Set<XposedInterface.HookHandle> watcherHandles =
            Collections.synchronizedSet(new HashSet<>());

    private volatile WeakReference<MediaSession> latestSession = new WeakReference<>(null);
    private volatile MediaMetadata latestMetadata;
    private volatile boolean installed;
    private volatile boolean watcherInstalled;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!SPOTIFY.equals(param.getProcessName())) {
            detach();
            return;
        }
        info("Spotify header compatibility layer loaded; API=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SPOTIFY.equals(param.getPackageName()) || installed) return;
        synchronized (this) {
            if (installed) return;
            installed = true;

            installMetadataReplayHook();
            int fastPathHooks = installKnownFastPaths(param.getClassLoader());
            if (!SpotifyHeaderStore.isReady()) installClassLoadWatcher();

            info("Spotify header compatibility hooks installed=" + fastPathHooks
                    + " structuralWatcher=" + watcherInstalled);
        }
    }

    private void installMetadataReplayHook() {
        try {
            Method method = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            method.setAccessible(true);
            hook(method)
                    .setId("colorlyric-compat-metadata-replay")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!Boolean.TRUE.equals(replayGuard.get())) {
                            Object instance = chain.getThisObject();
                            Object incoming = chain.getArg(0);
                            if (instance instanceof MediaSession && incoming instanceof MediaMetadata) {
                                rememberTrackMetadata((MediaSession) instance, (MediaMetadata) incoming);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            info("compat metadata hook unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void rememberTrackMetadata(MediaSession session, MediaMetadata metadata) {
        String mediaId;
        try {
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        } catch (Throwable ignored) {
            return;
        }
        if (mediaId == null || !mediaId.startsWith("spotify:track:")) return;
        latestSession = new WeakReference<>(session);
        latestMetadata = metadata;
    }

    private int installKnownFastPaths(ClassLoader classLoader) {
        int installedCount = 0;
        for (String className : HEADER_CONTAINER_FAST_PATHS) {
            installedCount += hookKnownHeaderContainer(classLoader, className);
        }
        for (String className : ADD_HEADER_FAST_PATHS) {
            installedCount += hookKnownAddHeader(classLoader, className);
        }
        return installedCount;
    }

    private int hookKnownHeaderContainer(ClassLoader classLoader, String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            return hookHeaderContainer(type);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int hookKnownAddHeader(ClassLoader classLoader, String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            return hookAddHeaderMethods(type);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int hookHeaderContainer(Class<?> type) {
        if (!SpotifyHeaderDiscoveryPolicy.isHeaderContainerType(type)) return 0;
        if (!hookedContainerClasses.add(type.getName())) return 0;

        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 1
                    || constructor.getParameterTypes()[0] != String[].class) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                hook(constructor)
                        .setId("colorlyric-compat-header-container")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            boolean becameReady = false;
                            for (Object arg : chain.getArgs()) {
                                becameReady |= SpotifyHeaderStore.ingestPairs(arg);
                            }
                            becameReady |= captureStringArrayFields(chain.getThisObject());
                            if (becameReady) onHeadersReady("container:" + type.getName());
                            return result;
                        });
                count++;
            } catch (Throwable error) {
                info("compat container hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }

        if (count > 0) {
            info("compat header container=" + type.getName() + " constructors=" + count);
            return count;
        }
        hookedContainerClasses.remove(type.getName());
        return 0;
    }

    private int hookAddHeaderMethods(Class<?> type) {
        if (!SpotifyHeaderDiscoveryPolicy.hasAddHeaderMethod(type)) return 0;

        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!SpotifyHeaderDiscoveryPolicy.isAddHeaderMethod(method)) continue;
            String key = type.getName() + "#" + method.toGenericString();
            if (!hookedHeaderMethods.add(key)) continue;
            try {
                method.setAccessible(true);
                hook(method)
                        .setId("colorlyric-compat-add-header")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            String name = chain.getArg(0) instanceof String
                                    ? (String) chain.getArg(0) : null;
                            String value = chain.getArg(1) instanceof String
                                    ? (String) chain.getArg(1) : null;
                            boolean becameReady = SpotifyHeaderStore.ingest(name, value);
                            if (becameReady) onHeadersReady("addHeader:" + type.getName());
                            return chain.proceed();
                        });
                count++;
            } catch (Throwable error) {
                hookedHeaderMethods.remove(key);
                info("compat addHeader hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }

        if (count > 0) info("compat addHeader=" + type.getName() + " methods=" + count);
        return count;
    }

    private void installClassLoadWatcher() {
        if (watcherInstalled) return;
        synchronized (this) {
            if (watcherInstalled || SpotifyHeaderStore.isReady()) return;
            watcherInstalled = true;

            for (Method method : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(method.getName())
                        || method.getParameterCount() < 1
                        || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedInterface.HookHandle handle = hook(method)
                            .setId("colorlyric-compat-header-discovery")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                if (!(result instanceof Class<?>)) return result;
                                if (Boolean.TRUE.equals(discoveryGuard.get())) return result;

                                Class<?> type = (Class<?>) result;
                                if (!SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace(
                                        type.getName())) {
                                    return result;
                                }

                                discoveryGuard.set(Boolean.TRUE);
                                try {
                                    int containers = hookHeaderContainer(type);
                                    int writers = hookAddHeaderMethods(type);
                                    if (containers > 0 || writers > 0) {
                                        info("structural Spotify header target=" + type.getName()
                                                + " containers=" + containers
                                                + " writers=" + writers);
                                    }
                                    if (SpotifyHeaderStore.isReady()) removeClassLoadWatcher();
                                } finally {
                                    discoveryGuard.remove();
                                }
                                return result;
                            });
                    watcherHandles.add(handle);
                } catch (Throwable error) {
                    info("compat class watcher failed: " + error.getClass().getSimpleName());
                }
            }

            if (watcherHandles.isEmpty()) {
                watcherInstalled = false;
                info("compat structural class watcher unavailable");
            } else {
                info("compat structural class watcher installed");
            }
        }
    }

    private boolean captureStringArrayFields(Object object) {
        if (object == null) return false;
        boolean becameReady = false;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String[].class) continue;
            try {
                field.setAccessible(true);
                becameReady |= SpotifyHeaderStore.ingestPairs(field.get(object));
            } catch (Throwable ignored) {
            }
        }
        return becameReady;
    }

    private void onHeadersReady(String source) {
        info("Spotify auth headers recovered via " + source
                + " keys=" + SpotifyHeaderStore.capturedKeys());
        removeClassLoadWatcher();
        replayLatestMetadata();
    }

    private void replayLatestMetadata() {
        MediaSession session = latestSession.get();
        MediaMetadata metadata = latestMetadata;
        if (session == null || metadata == null) return;

        try {
            String existing = metadata.getString(StockLyricInfo.KEY);
            if (existing != null && !existing.isBlank()) return;
        } catch (Throwable ignored) {
        }

        try {
            replayGuard.set(Boolean.TRUE);
            session.setMetadata(metadata);
            info("replayed Spotify metadata after auth header recovery");
        } catch (Throwable error) {
            info("compat metadata replay failed: " + error.getClass().getSimpleName());
        } finally {
            replayGuard.remove();
        }
    }

    private void removeClassLoadWatcher() {
        synchronized (this) {
            if (watcherHandles.isEmpty()) return;
            for (XposedInterface.HookHandle handle : new HashSet<>(watcherHandles)) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            watcherHandles.clear();
            watcherInstalled = false;
            info("compat structural class watcher removed");
        }
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }
}
