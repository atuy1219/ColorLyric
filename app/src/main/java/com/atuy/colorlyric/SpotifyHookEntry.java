/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class SpotifyHookEntry extends XposedModule {
    private static final String TAG = "ColorLyric";
    private static final String SPOTIFY = "com.spotify.music";

    private final Object stateLock = new Object();
    private final ThreadLocal<Boolean> moduleWrite = new ThreadLocal<>();
    private final ThreadLocal<Boolean> discoveryGuard = new ThreadLocal<>();
    private final AtomicLong generation = new AtomicLong();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, SpotifyColorLyricsClient.FetchCall> activeCalls = new ConcurrentHashMap<>();
    private final SpotifySessionRegistry registry = new SpotifySessionRegistry();
    private final AtomicInteger workerIndex = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "ColorLyric-SpotifyLyrics-" + workerIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 32;
                }
            }
    );
    private final Map<String, Long> negativeCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 64;
                }
            }
    );
    private final Set<String> hookedHeaderClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> hookedHeaderMethods = ConcurrentHashMap.newKeySet();
    private final Set<XposedInterface.HookHandle> headerWatcherHandles =
            Collections.synchronizedSet(new HashSet<>());

    private volatile String currentTrackKey = "";
    private volatile StockLyricInfo.TrackSnapshot currentTrack = StockLyricInfo.TrackSnapshot.empty();
    private volatile String committedTrackKey = "";
    private volatile long committedGeneration = -1L;
    private volatile boolean missingHeadersLogged;
    private volatile boolean hooksInstalled;
    private volatile boolean headerContainerHooked;
    private volatile boolean headerWatcherInstalled;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!SPOTIFY.equals(param.getProcessName())) {
            detach();
            return;
        }
        info("loaded in Spotify main process; API=" + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SPOTIFY.equals(param.getPackageName()) || hooksInstalled) return;
        synchronized (this) {
            if (hooksInstalled) return;
            hooksInstalled = true;
            installMediaSessionHooks();
            installSpotifyHeaderHooks(param.getClassLoader());
            info("Spotify hooks installed");
        }
    }

    private void installMediaSessionHooks() {
        for (Constructor<?> constructor : MediaSession.class.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            hook(constructor)
                    .setId("colorlyric-spotify-session-ctor")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object instance = chain.getThisObject();
                        if (instance instanceof MediaSession) {
                            MediaSession session = (MediaSession) instance;
                            String tag = SpotifySessionRegistry.constructorTag(
                                    chain.getArgs().toArray());
                            registry.onConstructed(session, tag);
                            info("session constructed tag=" + tag
                                    + " cast=" + SpotifySessionRegistry.isCastTag(tag));
                        }
                        return result;
                    });
        }

        try {
            Method method = MediaSession.class.getDeclaredMethod("setMetadata", MediaMetadata.class);
            method.setAccessible(true);
            hook(method)
                    .setId("colorlyric-spotify-set-metadata")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (Boolean.TRUE.equals(moduleWrite.get())) return chain.proceed();
                        Object instance = chain.getThisObject();
                        Object incoming = chain.getArg(0);
                        if (!(instance instanceof MediaSession) || !(incoming instanceof MediaMetadata)) {
                            return chain.proceed();
                        }
                        MediaMetadata outgoing = onMetadata(
                                (MediaSession) instance, (MediaMetadata) incoming);
                        if (outgoing == incoming) return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        args[0] = outgoing;
                        return chain.proceed(args);
                    });
        } catch (ReflectiveOperationException error) {
            info("setMetadata hook unavailable: " + error.getClass().getSimpleName());
        }

        try {
            Method method = MediaSession.class.getDeclaredMethod(
                    "setPlaybackState", PlaybackState.class);
            method.setAccessible(true);
            hook(method)
                    .setId("colorlyric-spotify-set-playback-state")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object instance = chain.getThisObject();
                        Object state = chain.getArg(0);
                        if (instance instanceof MediaSession && state instanceof PlaybackState) {
                            MediaSession session = (MediaSession) instance;
                            registry.onPlaybackState(session, ((PlaybackState) state).getState());
                            publishCachedOnceIfReady(session, "playback-ready");
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException error) {
            info("setPlaybackState hook unavailable: " + error.getClass().getSimpleName());
        }

        try {
            Method method = MediaSession.class.getDeclaredMethod("setActive", boolean.class);
            method.setAccessible(true);
            hook(method)
                    .setId("colorlyric-spotify-set-active")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object instance = chain.getThisObject();
                        Object active = chain.getArg(0);
                        if (instance instanceof MediaSession && active instanceof Boolean) {
                            MediaSession session = (MediaSession) instance;
                            registry.onActive(session, (Boolean) active);
                            publishCachedOnceIfReady(session, "active-ready");
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException error) {
            info("setActive hook unavailable: " + error.getClass().getSimpleName());
        }

        try {
            Method method = MediaSession.class.getDeclaredMethod("release");
            method.setAccessible(true);
            hook(method)
                    .setId("colorlyric-spotify-release")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object instance = chain.getThisObject();
                        if (instance instanceof MediaSession) {
                            registry.onReleased((MediaSession) instance);
                        }
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException error) {
            info("release hook unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void installSpotifyHeaderHooks(ClassLoader classLoader) {
        int installed = 0;
        installed += hookKnownHeaderContainer(classLoader, "p.ot10");
        installed += hookKnownHeaderContainer(classLoader, "okhttp3.Headers");
        installed += hookAddHeader(classLoader, "p.aj81");
        installed += hookAddHeader(classLoader, "org.chromium.net.UrlRequest$Builder");

        if (!headerContainerHooked) {
            installHeaderClassLoadWatcher();
        }
        info("Spotify auth header hooks installed=" + installed
                + " structuralWatcher=" + headerWatcherInstalled);
    }

    private int hookKnownHeaderContainer(ClassLoader classLoader, String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            return hookHeaderContainer(type, false);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int hookHeaderContainer(Class<?> type, boolean requireStructuralMatch) {
        if (type == null) return 0;
        if (requireStructuralMatch && !isShadedHeadersType(type)) return 0;
        if (!hasInstanceStringArrayField(type)) return 0;
        if (!hookedHeaderClasses.add(type.getName())) return 0;

        int count = 0;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (requireStructuralMatch
                    && !(constructor.getParameterCount() == 1
                    && constructor.getParameterTypes()[0] == String[].class)) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                hook(constructor)
                        .setId("colorlyric-header-container")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            boolean becameReady = false;
                            for (Object arg : chain.getArgs()) {
                                becameReady |= SpotifyHeaderStore.ingestPairs(arg);
                            }
                            becameReady |= captureStringArrayFields(chain.getThisObject());
                            if (becameReady) onHeadersReady();
                            return result;
                        });
                count++;
            } catch (Throwable error) {
                info("header constructor hook failed " + type.getName() + ": "
                        + error.getClass().getSimpleName());
            }
        }

        if (count > 0) {
            headerContainerHooked = true;
            info("header container hook=" + type.getName() + " constructors=" + count);
            removeHeaderClassLoadWatcher();
            return count;
        }
        hookedHeaderClasses.remove(type.getName());
        return 0;
    }

    private int hookAddHeader(ClassLoader classLoader, String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            int count = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!"addHeader".equals(method.getName()) || method.getParameterCount() != 2) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters[0] != String.class || parameters[1] != String.class) continue;
                String key = type.getName() + "#" + method.toGenericString();
                if (!hookedHeaderMethods.add(key)) continue;
                method.setAccessible(true);
                hook(method)
                        .setId("colorlyric-add-header")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            String name = chain.getArg(0) instanceof String
                                    ? (String) chain.getArg(0) : null;
                            String value = chain.getArg(1) instanceof String
                                    ? (String) chain.getArg(1) : null;
                            if (SpotifyHeaderStore.ingest(name, value)) onHeadersReady();
                            return chain.proceed();
                        });
                count++;
            }
            if (count > 0) info("addHeader hook=" + className + " methods=" + count);
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void installHeaderClassLoadWatcher() {
        if (headerWatcherInstalled) return;
        synchronized (this) {
            if (headerWatcherInstalled) return;
            headerWatcherInstalled = true;
            for (Method method : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(method.getName())) continue;
                if (method.getParameterCount() < 1 || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedInterface.HookHandle handle = hook(method)
                            .setId("colorlyric-spotify-header-discovery")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                if (!(result instanceof Class<?>)) return result;
                                if (Boolean.TRUE.equals(discoveryGuard.get())) return result;
                                discoveryGuard.set(Boolean.TRUE);
                                try {
                                    hookHeaderContainer((Class<?>) result, true);
                                } finally {
                                    discoveryGuard.remove();
                                }
                                return result;
                            });
                    headerWatcherHandles.add(handle);
                } catch (Throwable error) {
                    info("header discovery watcher method failed: "
                            + error.getClass().getSimpleName());
                }
            }
            if (headerWatcherHandles.isEmpty()) {
                headerWatcherInstalled = false;
                info("header discovery watcher unavailable");
            } else {
                info("structural header discovery watcher installed");
            }
        }
    }

    private void removeHeaderClassLoadWatcher() {
        synchronized (this) {
            if (headerWatcherHandles.isEmpty()) return;
            for (XposedInterface.HookHandle handle : new HashSet<>(headerWatcherHandles)) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            headerWatcherHandles.clear();
            headerWatcherInstalled = false;
            info("structural header discovery watcher removed");
        }
    }

    private static boolean isShadedHeadersType(Class<?> type) {
        if (Modifier.isAbstract(type.getModifiers()) || !Iterable.class.isAssignableFrom(type)) {
            return false;
        }
        int stringArrayFields = 0;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String[].class) {
                stringArrayFields++;
            }
        }
        if (stringArrayFields != 1) return false;

        boolean hasIterator = false;
        boolean hasStringLookup = false;
        for (Method method : type.getDeclaredMethods()) {
            if ("iterator".equals(method.getName()) && method.getParameterCount() == 0) {
                hasIterator = true;
            }
            if (method.getReturnType() == String.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class) {
                hasStringLookup = true;
            }
        }
        if (!hasIterator || !hasStringLookup) return false;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1
                    && constructor.getParameterTypes()[0] == String[].class) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInstanceStringArrayField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String[].class) {
                return true;
            }
        }
        return false;
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

    private void onHeadersReady() {
        missingHeadersLogged = false;
        removeHeaderClassLoadWatcher();
        info("Spotify auth headers ready keys=" + SpotifyHeaderStore.capturedKeys());
        scheduleCurrentFetch("headers-ready");
    }

    private MediaMetadata onMetadata(MediaSession session, MediaMetadata metadata) {
        StockLyricInfo.TrackSnapshot sessionTrack = registry.onHostMetadata(session, metadata);
        if (registry.isCast(session)) return metadata;

        Observation observation = observe(sessionTrack);
        StockLyricInfo.TrackSnapshot track = observation.track;
        String existing = safeLyricInfo(metadata);

        if (existing != null && !existing.isBlank()) {
            String accepted = StockLyricInfo.normalize(existing, track);
            if (accepted != null && track.hasSpotifyTrackId()) {
                cache.put(track.key(), accepted);
                markCommitted(track.key(), observation.generation);
                info("existing native lyricInfo observed: " + shortId(track.mediaId));
                return metadata;
            }
            if (track.hasSpotifyTrackId()) {
                info("stale or invalid lyricInfo ignored: " + shortId(track.mediaId));
            }
        }

        if (!track.hasSpotifyTrackId()) return metadata;

        String cached = cache.get(track.key());
        if (cached != null) {
            cached = StockLyricInfo.rebindGeneration(cached, observation.generation);
            MediaMetadata patched = StockLyricInfo.withLyricInfo(metadata, cached);
            int parcelBytes = StockLyricInfo.parcelSize(patched);
            if (parcelBytes > 0 && parcelBytes <= StockLyricInfo.MAX_PARCEL_BYTES) {
                registry.onHostMetadata(session, patched);
                markCommitted(track.key(), observation.generation);
                info("cached official lyricInfo attached to host metadata: "
                        + shortId(track.mediaId) + " parcel=" + parcelBytes);
                return patched;
            }
            return metadata;
        }

        if (!track.hasQueryIdentity()) {
            info("metadata incomplete: " + track.debugSummary());
            return metadata;
        }

        scheduleFetch(track.key(), observation.generation, "metadata");
        return metadata;
    }

    private Observation observe(StockLyricInfo.TrackSnapshot incoming) {
        synchronized (stateLock) {
            boolean incomingHasId = incoming.hasSpotifyTrackId();
            if (incomingHasId && !incoming.mediaId.equals(currentTrackKey)) {
                currentTrackKey = incoming.mediaId;
                currentTrack = incoming;
                committedTrackKey = "";
                committedGeneration = -1L;
                missingHeadersLogged = false;
                long nextGeneration = generation.incrementAndGet();
                String requestKey = SpotifyFetchPolicy.requestKey(currentTrackKey, nextGeneration);
                cancelStaleFetches(requestKey);
                info("track=" + shortId(incoming.mediaId) + " generation=" + nextGeneration
                        + " " + incoming.debugSummary());
                return new Observation(currentTrack, nextGeneration);
            }

            if (incomingHasId && incoming.mediaId.equals(currentTrackKey)) {
                currentTrack = currentTrack.merge(incoming);
            } else if (!incomingHasId && !currentTrackKey.isBlank()) {
                currentTrack = currentTrack.merge(incoming);
            } else if (currentTrackKey.isBlank()) {
                currentTrack = incoming;
            }
            return new Observation(currentTrack, generation.get());
        }
    }

    private void scheduleCurrentFetch(String reason) {
        StockLyricInfo.TrackSnapshot track;
        String trackKey;
        long currentGeneration;
        synchronized (stateLock) {
            track = currentTrack;
            trackKey = currentTrackKey;
            currentGeneration = generation.get();
        }
        if (track == null || trackKey.isBlank() || !track.hasQueryIdentity()) return;
        scheduleFetch(trackKey, currentGeneration, reason);
    }

    private void scheduleFetch(String trackKey, long currentGeneration, String reason) {
        if (!SpotifyHeaderStore.isReady()) {
            if (!missingHeadersLogged) {
                missingHeadersLogged = true;
                info("Spotify Color Lyrics waiting for auth headers keys="
                        + SpotifyHeaderStore.capturedKeys());
            }
            return;
        }

        long now = System.currentTimeMillis();
        Long negativeUntil = negativeCache.get(trackKey);
        if (SpotifyFetchPolicy.isNegativeCached(negativeUntil, now)) return;
        if (negativeUntil != null) negativeCache.remove(trackKey);

        String requestKey = SpotifyFetchPolicy.requestKey(trackKey, currentGeneration);
        if (!inFlight.add(requestKey)) return;

        SpotifyColorLyricsClient.FetchCall call = new SpotifyColorLyricsClient.FetchCall();
        activeCalls.put(requestKey, call);
        executor.execute(() -> {
            try {
                StockLyricInfo.TrackSnapshot track = snapshotCurrent(trackKey, currentGeneration);
                if (track == null || call.isCancelled()) return;

                String cached = cache.get(trackKey);
                if (cached != null) {
                    publish(trackKey, currentGeneration,
                            StockLyricInfo.rebindGeneration(cached, currentGeneration),
                            "cache-before-fetch");
                    return;
                }

                SpotifyColorLyricsClient.Result result = SpotifyColorLyricsClient.fetch(
                        track, SpotifyHeaderStore.snapshot(), call);
                if ("cancelled".equals(result.outcome)) return;

                info("Spotify Color Lyrics outcome=" + result.outcome
                        + " syncType=" + result.syncType
                        + " source=" + result.source
                        + " reason=" + reason
                        + " track=" + shortId(track.mediaId));

                if ("http-401".equals(result.outcome)) {
                    SpotifyHeaderStore.invalidateAuthorization();
                    missingHeadersLogged = false;
                    return;
                }

                long ttl = SpotifyFetchPolicy.negativeTtlMs(result.outcome);
                if (ttl > 0L) {
                    negativeCache.put(trackKey, System.currentTimeMillis() + ttl);
                    return;
                }
                if (!result.isSuccess()) return;

                String payload = StockLyricInfo.build(
                        track,
                        result.lineLyric,
                        result.rawLyric,
                        currentGeneration,
                        result.syncType,
                        result.source);
                if (payload == null || !isCurrent(trackKey, currentGeneration)) return;
                negativeCache.remove(trackKey);
                cache.put(trackKey, payload);
                publish(trackKey, currentGeneration, payload, "spotify-color-lyrics");
            } catch (Throwable error) {
                if (!call.isCancelled()) {
                    info("Spotify Color Lyrics fetch failed: "
                            + error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            } finally {
                activeCalls.remove(requestKey, call);
                inFlight.remove(requestKey);
            }
        });
    }

    private void cancelStaleFetches(String currentRequestKey) {
        for (Map.Entry<String, SpotifyColorLyricsClient.FetchCall> entry : activeCalls.entrySet()) {
            if (entry.getKey().equals(currentRequestKey)) continue;
            entry.getValue().cancel();
        }
    }

    private void publishCachedOnceIfReady(MediaSession session, String reason) {
        if (Boolean.TRUE.equals(moduleWrite.get())) return;
        SpotifySessionRegistry.Selection selection = registry.selectionFor(session);
        if (selection == null || !selection.track.hasSpotifyTrackId()) return;
        if (!selection.active || !SpotifySessionRegistry.isPlaybackStateValid(selection.playbackState)) return;

        String trackKey = selection.track.key();
        long currentGeneration = generation.get();
        if (!isCurrent(trackKey, currentGeneration) || isCommitted(trackKey, currentGeneration)) return;

        String payload = cache.get(trackKey);
        if (payload == null) return;
        payload = StockLyricInfo.rebindGeneration(payload, currentGeneration);
        publishToSelection(trackKey, currentGeneration, payload, selection, reason);
    }

    private StockLyricInfo.TrackSnapshot snapshotCurrent(String trackKey, long currentGeneration) {
        synchronized (stateLock) {
            if (!isCurrent(trackKey, currentGeneration)) return null;
            return currentTrack;
        }
    }

    private void publish(String trackKey, long currentGeneration, String payload, String reason) {
        if (!isCurrent(trackKey, currentGeneration) || isCommitted(trackKey, currentGeneration)) return;
        SpotifySessionRegistry.Selection selection = registry.select(trackKey);
        if (selection == null) {
            info("publish pending; no unique main session: " + registry.describe());
            return;
        }
        publishToSelection(trackKey, currentGeneration, payload, selection, reason);
    }

    private void publishToSelection(
            String trackKey,
            long currentGeneration,
            String payload,
            SpotifySessionRegistry.Selection selection,
            String reason) {
        if (!isCurrent(trackKey, currentGeneration) || isCommitted(trackKey, currentGeneration)) return;
        if (selection == null || selection.session == null || selection.metadata == null) return;
        if (SpotifySessionRegistry.isCastTag(selection.tag)) return;
        if (selection.track.hasSpotifyTrackId() && !trackKey.equals(selection.track.key())) return;

        try {
            MediaMetadata patched = StockLyricInfo.withLyricInfo(selection.metadata, payload);
            int parcelBytes = StockLyricInfo.parcelSize(patched);
            if (parcelBytes <= 0) {
                info("publish rejected; parcel measurement failed");
                return;
            }
            if (parcelBytes > StockLyricInfo.MAX_PARCEL_BYTES) {
                info("publish rejected; parcel too large=" + parcelBytes);
                return;
            }

            moduleWrite.set(Boolean.TRUE);
            selection.session.setMetadata(patched);
            registry.onHostMetadata(selection.session, patched);
            markCommitted(trackKey, currentGeneration);
            info("official Spotify lyricInfo committed once: " + shortId(trackKey)
                    + " reason=" + reason
                    + " tag=" + selection.tag
                    + " active=" + selection.active
                    + " state=" + selection.playbackState
                    + " parcel=" + parcelBytes);
        } catch (Throwable error) {
            info("publish failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            moduleWrite.remove();
        }
    }

    private boolean isCurrent(String trackKey, long currentGeneration) {
        return currentGeneration == generation.get() && trackKey.equals(currentTrackKey);
    }

    private boolean isCommitted(String trackKey, long currentGeneration) {
        return currentGeneration == committedGeneration && trackKey.equals(committedTrackKey);
    }

    private void markCommitted(String trackKey, long currentGeneration) {
        committedTrackKey = trackKey;
        committedGeneration = currentGeneration;
    }

    private static String safeLyricInfo(MediaMetadata metadata) {
        try {
            return metadata.getString(StockLyricInfo.KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shortId(String value) {
        if (value == null) return "";
        int index = value.lastIndexOf(':');
        String id = index >= 0 ? value.substring(index + 1) : value;
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private static final class Observation {
        final StockLyricInfo.TrackSnapshot track;
        final long generation;

        Observation(StockLyricInfo.TrackSnapshot track, long generation) {
            this.track = track;
            this.generation = generation;
        }
    }
}
