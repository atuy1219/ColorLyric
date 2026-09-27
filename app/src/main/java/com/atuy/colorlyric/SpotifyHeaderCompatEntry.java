/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexFile;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Compatibility layer for Spotify's frequently renamed networking classes.
 *
 * <p>Discovery does not depend on Spotify's R8 class names. It starts in
 * onPackageLoaded, watches network classes by structure, hooks stable Cronet APIs,
 * then hooks the actual builder class returned at runtime.</p>
 */
public final class SpotifyHeaderCompatEntry extends XposedModule {
    private static final String TAG = "ColorLyric";
    private static final String SPOTIFY = "com.spotify.music";

    private final ThreadLocal<Boolean> discoveryGuard = new ThreadLocal<>();
    private final ThreadLocal<Boolean> replayGuard = new ThreadLocal<>();
    private final Set<String> hookedContainerClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> hookedHeaderMethods = ConcurrentHashMap.newKeySet();
    private final Set<String> hookedCronetFactoryMethods = ConcurrentHashMap.newKeySet();
    private final Set<XposedInterface.HookHandle> watcherHandles =
            Collections.synchronizedSet(new HashSet<>());

    private volatile WeakReference<MediaSession> latestSession = new WeakReference<>(null);
    private volatile MediaMetadata latestMetadata;
    private volatile boolean metadataHookInstalled;
    private volatile boolean packageReadyInstalled;
    private volatile boolean watcherInstalled;
    private volatile boolean cronetFactoryHooked;
    private volatile boolean dexScanStarted;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!SPOTIFY.equals(param.getProcessName())) {
            detach();
            return;
        }

        // Start structural discovery as early as possible. By onPackageLoaded,
        // Spotify may already have loaded its shaded/obfuscated networking
        // implementation classes, which would make a loadClass watcher miss them.
        installClassLoadWatcher();
        info("Spotify update-resilient network discovery loaded; API=" + getApiVersion()
                + " earlyWatcher=" + watcherInstalled);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!SPOTIFY.equals(param.getPackageName())) return;
        synchronized (this) {
            installMetadataReplayHook();
            installClassLoadWatcher();
            int stable = inspectStableNetworkAnchors(param.getDefaultClassLoader());
            info("early Spotify network discovery installed stableHooks=" + stable
                    + " watcher=" + watcherInstalled);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SPOTIFY.equals(param.getPackageName()) || packageReadyInstalled) return;
        synchronized (this) {
            if (packageReadyInstalled) return;
            packageReadyInstalled = true;

            installMetadataReplayHook();
            ClassLoader loader = param.getClassLoader();
            int stable = inspectStableNetworkAnchors(loader);
            installClassLoadWatcher();
            startDexStructureScan(loader);
            retireDiscoveryWatcherIfStable();

            info("Spotify network compatibility ready stableHooks=" + stable
                    + " cronetFactory=" + cronetFactoryHooked
                    + " structuralWatcher=" + watcherInstalled);
        }
    }

    private void installMetadataReplayHook() {
        if (metadataHookInstalled) return;
        synchronized (this) {
            if (metadataHookInstalled) return;
            try {
                Method method = MediaSession.class.getDeclaredMethod(
                        "setMetadata", MediaMetadata.class);
                method.setAccessible(true);
                hook(method)
                        .setId("colorlyric-compat-metadata-replay")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!Boolean.TRUE.equals(replayGuard.get())) {
                                Object instance = chain.getThisObject();
                                Object incoming = chain.getArg(0);
                                if (instance instanceof MediaSession
                                        && incoming instanceof MediaMetadata) {
                                    rememberTrackMetadata(
                                            (MediaSession) instance,
                                            (MediaMetadata) incoming);
                                }
                            }
                            return chain.proceed();
                        });
                metadataHookInstalled = true;
            } catch (Throwable error) {
                info("compat metadata hook unavailable: "
                        + error.getClass().getSimpleName());
            }
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

    private int inspectStableNetworkAnchors(ClassLoader classLoader) {
        int installed = 0;
        installed += inspectKnownClass(classLoader, "org.chromium.net.CronetEngine");
        installed += inspectKnownClass(classLoader, "org.chromium.net.ExperimentalCronetEngine");
        installed += inspectKnownClass(classLoader, "org.chromium.net.UrlRequest$Builder");
        installed += inspectKnownClass(classLoader, "okhttp3.Headers");
        return installed;
    }

    private int inspectKnownClass(ClassLoader classLoader, String className) {
        try {
            return inspectNetworkType(Class.forName(className, false, classLoader));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int inspectNetworkType(Class<?> type) {
        if (type == null) return 0;
        int containers = hookHeaderContainer(type);
        int writers = hookAddHeaderMethods(type);
        int factories = hookCronetFactoryMethods(type);
        if (containers > 0 || writers > 0 || factories > 0) {
            info("Spotify network target=" + type.getName()
                    + " containers=" + containers
                    + " writers=" + writers
                    + " factories=" + factories);
        }
        return containers + writers + factories;
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
                            if (becameReady) onHeadersReady(
                                    "container:" + type.getName());
                            return result;
                        });
                count++;
            } catch (Throwable error) {
                info("compat container hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }

        if (count == 0) hookedContainerClasses.remove(type.getName());
        return count;
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
                            if (becameReady) {
                                onHeadersReady("addHeader:" + type.getName());
                            }
                            return chain.proceed();
                        });
                count++;
            } catch (Throwable error) {
                hookedHeaderMethods.remove(key);
                info("compat addHeader hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }
        return count;
    }

    private int hookCronetFactoryMethods(Class<?> type) {
        if (!SpotifyHeaderDiscoveryPolicy.isCronetEngineSubtype(type)) return 0;

        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!SpotifyHeaderDiscoveryPolicy.isCronetRequestBuilderFactory(method)) continue;
            String key = type.getName() + "#" + method.toGenericString();
            if (!hookedCronetFactoryMethods.add(key)) continue;
            try {
                method.setAccessible(true);
                hook(method)
                        .setId("colorlyric-cronet-request-factory")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            String url = chain.getArg(0) instanceof String
                                    ? (String) chain.getArg(0) : null;
                            Object builder = chain.proceed();

                            if (builder != null) {
                                int writers = hookAddHeaderMethods(builder.getClass());
                                if (writers > 0) {
                                    info("runtime Cronet builder="
                                            + builder.getClass().getName()
                                            + " writers=" + writers);
                                }
                            }

                            if (SpotifyEndpointStore.observeUrl(url)) {
                                info("learned Spotify Color Lyrics endpoint revision="
                                        + SpotifyEndpointStore.revision());
                                if (SpotifyHeaderStore.isReady()) replayLatestMetadata();
                            }
                            retireDiscoveryWatcherIfStable();
                            return builder;
                        });
                count++;
            } catch (Throwable error) {
                hookedCronetFactoryMethods.remove(key);
                info("Cronet factory hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }

        if (count > 0) {
            cronetFactoryHooked = true;
            retireDiscoveryWatcherIfStable();
        }
        return count;
    }

    private void installClassLoadWatcher() {
        if (watcherInstalled) return;
        synchronized (this) {
            if (watcherInstalled) return;
            watcherInstalled = true;

            installClassResolverWatcher(ClassLoader.class, "loadClass");
            try {
                Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
                installClassResolverWatcher(baseDex, "findClass");
            } catch (Throwable error) {
                info("BaseDexClassLoader watcher unavailable: "
                        + error.getClass().getSimpleName());
            }

            if (watcherHandles.isEmpty()) {
                watcherInstalled = false;
                info("compat structural class watcher unavailable");
            } else {
                info("compat structural class watcher installed handles="
                        + watcherHandles.size());
            }
        }
    }

    private void installClassResolverWatcher(Class<?> owner, String methodName) {
        for (Method method : owner.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())
                    || method.getParameterCount() < 1
                    || method.getParameterTypes()[0] != String.class
                    || method.getReturnType() != Class.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedInterface.HookHandle handle = hook(method)
                        .setId("colorlyric-compat-network-discovery-" + methodName)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result instanceof Class<?>) inspectResolvedType((Class<?>) result);
                            return result;
                        });
                watcherHandles.add(handle);
            } catch (Throwable error) {
                info("compat " + owner.getSimpleName() + "." + methodName
                        + " watcher failed: " + error.getClass().getSimpleName());
            }
        }
    }

    private void inspectResolvedType(Class<?> type) {
        if (type == null || Boolean.TRUE.equals(discoveryGuard.get())) return;
        if (!SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace(type.getName())) return;

        discoveryGuard.set(Boolean.TRUE);
        try {
            inspectNetworkType(type);
            retireDiscoveryWatcherIfStable();
        } finally {
            discoveryGuard.remove();
        }
    }

    private void startDexStructureScan(ClassLoader loader) {
        if (dexScanStarted) return;
        synchronized (this) {
            if (dexScanStarted) return;
            dexScanStarted = true;
        }

        Thread thread = new Thread(() -> scanSpotifyDex(loader),
                "ColorLyric-SpotifyDexDiscovery");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void scanSpotifyDex(ClassLoader loader) {
        long started = System.currentTimeMillis();
        int dexCount = 0;
        int candidates = 0;
        int loaded = 0;
        int installed = 0;

        try {
            Application application = currentApplication();
            if (application == null) {
                info("Spotify DEX structural scan skipped: application unavailable");
                return;
            }

            ApplicationInfo appInfo = application.getApplicationInfo();
            String[] splitDirs = appInfo.splitSourceDirs;
            int totalPaths = 1 + (splitDirs == null ? 0 : splitDirs.length);
            String[] paths = new String[totalPaths];
            paths[0] = appInfo.sourceDir;
            if (splitDirs != null) {
                System.arraycopy(splitDirs, 0, paths, 1, splitDirs.length);
            }

            for (String apkPath : paths) {
                if (apkPath == null || apkPath.isBlank()) continue;
                DexFile dex = null;
                try {
                    dex = new DexFile(apkPath);
                    dexCount++;
                    Enumeration<String> entries = dex.entries();
                    while (entries.hasMoreElements()) {
                        String className = entries.nextElement();
                        if (!SpotifyHeaderDiscoveryPolicy.isSpotifyNetworkNamespace(className)) {
                            continue;
                        }
                        candidates++;

                        Class<?> type = null;
                        discoveryGuard.set(Boolean.TRUE);
                        try {
                            type = Class.forName(className, false, loader);
                            loaded++;
                        } catch (Throwable ignored) {
                        } finally {
                            discoveryGuard.remove();
                        }
                        if (type == null) continue;

                        installed += inspectNetworkType(type);
                    }
                } catch (Throwable error) {
                    info("Spotify DEX scan path failed: "
                            + error.getClass().getSimpleName());
                } finally {
                    if (dex != null) {
                        try {
                            dex.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable error) {
            info("Spotify DEX structural scan failed: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            info("Spotify DEX structural scan finished dex=" + dexCount
                    + " candidates=" + candidates
                    + " loaded=" + loaded
                    + " hooks=" + installed
                    + " containers=" + hookedContainerClasses.size()
                    + " writers=" + hookedHeaderMethods.size()
                    + " cronetFactories=" + hookedCronetFactoryMethods.size()
                    + " ms=" + (System.currentTimeMillis() - started));
            retireDiscoveryWatcherIfStable();
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean captureStringArrayFields(Object object) {
        if (object == null) return false;
        boolean becameReady = false;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.getType() != String[].class) {
                continue;
            }
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
        replayLatestMetadata();
        retireDiscoveryWatcherIfStable();
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
            info("replayed Spotify metadata after network discovery update");
        } catch (Throwable error) {
            info("compat metadata replay failed: "
                    + error.getClass().getSimpleName());
        } finally {
            replayGuard.remove();
        }
    }

    private void retireDiscoveryWatcherIfStable() {
        if (!SpotifyHeaderStore.isReady() || !cronetFactoryHooked) return;
        removeClassLoadWatcher();
    }

    private void removeClassLoadWatcher() {
        synchronized (this) {
            if (watcherHandles.isEmpty()) return;
            for (XposedInterface.HookHandle handle :
                    new HashSet<>(watcherHandles)) {
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
