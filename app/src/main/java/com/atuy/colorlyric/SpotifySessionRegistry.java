/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class SpotifySessionRegistry {
    private static final class Entry {
        final WeakReference<MediaSession> session;
        String tag;
        StockLyricInfo.TrackSnapshot track = StockLyricInfo.TrackSnapshot.empty();
        MediaMetadata metadata;
        int playbackState = PlaybackState.STATE_NONE;
        boolean active;
        boolean released;

        Entry(MediaSession session, String tag) {
            this.session = new WeakReference<>(session);
            this.tag = tag == null ? "" : tag;
        }
    }

    static final class Selection {
        final MediaSession session;
        final String tag;
        final StockLyricInfo.TrackSnapshot track;
        final MediaMetadata metadata;
        final int playbackState;
        final boolean active;

        Selection(MediaSession session, String tag, StockLyricInfo.TrackSnapshot track,
                  MediaMetadata metadata, int playbackState, boolean active) {
            this.session = session;
            this.tag = tag;
            this.track = track;
            this.metadata = metadata;
            this.playbackState = playbackState;
            this.active = active;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    synchronized void onConstructed(MediaSession session, String tag) {
        prune();
        Entry entry = find(session);
        if (entry == null) {
            entries.add(new Entry(session, tag));
        } else if ((entry.tag == null || entry.tag.isBlank()) && tag != null) {
            entry.tag = tag;
        }
    }

    synchronized StockLyricInfo.TrackSnapshot onHostMetadata(MediaSession session, MediaMetadata metadata) {
        Entry entry = ensure(session);
        StockLyricInfo.TrackSnapshot incoming = StockLyricInfo.TrackSnapshot.from(metadata);
        if (incoming.hasSpotifyTrackId() && entry.track.hasSpotifyTrackId()
                && !incoming.mediaId.equals(entry.track.mediaId)) {
            entry.track = incoming;
        } else {
            entry.track = entry.track.merge(incoming);
        }
        entry.metadata = metadata;
        return entry.track;
    }

    synchronized void onPlaybackState(MediaSession session, int state) {
        ensure(session).playbackState = state;
    }

    synchronized void onActive(MediaSession session, boolean active) {
        ensure(session).active = active;
    }

    synchronized void onReleased(MediaSession session) {
        Entry entry = find(session);
        if (entry != null) entry.released = true;
    }

    synchronized boolean isCast(MediaSession session) {
        Entry entry = find(session);
        return entry != null && isCastTag(entry.tag);
    }

    synchronized Selection select(String trackKey) {
        prune();
        Selection selection;

        selection = unique(trackKey, true, true);
        if (selection != null) return selection;

        selection = unique(trackKey, false, true);
        if (selection != null) return selection;

        selection = unique(trackKey, true, false);
        if (selection != null) return selection;

        selection = unique(trackKey, false, false);
        if (selection != null) return selection;

        return uniqueAnyLive();
    }

    synchronized Selection selectionFor(MediaSession session) {
        prune();
        Entry entry = find(session);
        if (entry == null || entry.released || isCastTag(entry.tag)) return null;
        MediaSession live = entry.session.get();
        if (live == null) return null;
        return toSelection(entry, live);
    }

    synchronized String describe() {
        prune();
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            MediaSession session = entry.session.get();
            if (session == null || entry.released) continue;
            if (out.length() > 0) out.append(" | ");
            out.append("tag=").append(entry.tag)
                    .append(" cast=").append(isCastTag(entry.tag))
                    .append(" active=").append(entry.active)
                    .append(" state=").append(entry.playbackState)
                    .append(" track=").append(shortId(entry.track.mediaId));
        }
        return out.toString();
    }

    private Selection unique(String trackKey, boolean requireActive, boolean requireValidState) {
        Selection found = null;
        int count = 0;
        for (Entry entry : entries) {
            MediaSession session = entry.session.get();
            if (session == null || entry.released || isCastTag(entry.tag)) continue;
            if (!trackKey.equals(entry.track.key())) continue;
            if (requireActive && !entry.active) continue;
            if (requireValidState && !isPlaybackStateValid(entry.playbackState)) continue;
            found = toSelection(entry, session);
            count++;
        }
        return count == 1 ? found : null;
    }

    private Selection uniqueAnyLive() {
        Selection found = null;
        int count = 0;
        for (Entry entry : entries) {
            MediaSession session = entry.session.get();
            if (session == null || entry.released || isCastTag(entry.tag)) continue;
            if (!entry.active || !isPlaybackStateValid(entry.playbackState)) continue;
            found = toSelection(entry, session);
            count++;
        }
        if (count == 1) return found;

        found = null;
        count = 0;
        for (Entry entry : entries) {
            MediaSession session = entry.session.get();
            if (session == null || entry.released || isCastTag(entry.tag)) continue;
            found = toSelection(entry, session);
            count++;
        }
        return count == 1 ? found : null;
    }

    private Selection toSelection(Entry entry, MediaSession session) {
        MediaMetadata live = null;
        try {
            live = session.getController().getMetadata();
        } catch (Throwable ignored) {
        }
        if (live == null) live = entry.metadata;
        return new Selection(session, entry.tag, entry.track, live,
                entry.playbackState, entry.active);
    }

    private Entry ensure(MediaSession session) {
        Entry entry = find(session);
        if (entry == null) {
            entry = new Entry(session, "");
            entries.add(entry);
        }
        return entry;
    }

    private Entry find(MediaSession session) {
        for (Entry entry : entries) {
            if (entry.session.get() == session) return entry;
        }
        return null;
    }

    private void prune() {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.released || entry.session.get() == null) iterator.remove();
        }
    }

    static boolean isPlaybackStateValid(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }

    static String constructorTag(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) {
            if (arg instanceof String) return (String) arg;
        }
        return "";
    }

    static boolean isCastTag(String tag) {
        String normalized = tag == null ? "" : tag.toLowerCase();
        return !normalized.isBlank()
                && (normalized.contains("cast") || normalized.contains("gms"));
    }

    private static String shortId(String value) {
        if (value == null || value.isBlank()) return "-";
        int index = value.lastIndexOf(':');
        String id = index >= 0 ? value.substring(index + 1) : value;
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
