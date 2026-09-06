package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class StockLyricInfoTest {
    @Test
    public void normalizeRejectsDifferentSpotifyTrack() {
        StockLyricInfo.TrackSnapshot track = new StockLyricInfo.TrackSnapshot(
                "spotify:track:current", "Title", "Artist", "Album", 180_000L);
        String stale = "{\"songId\":\"spotify:track:old\",\"lyric\":\"[00:01.000]line\"}";
        assertNull(StockLyricInfo.normalize(stale, track));
    }

    @Test
    public void normalizeAcceptsCurrentTrack() {
        StockLyricInfo.TrackSnapshot track = new StockLyricInfo.TrackSnapshot(
                "spotify:track:current", "Title", "Artist", "Album", 180_000L);
        String value = "{\"songId\":\"spotify:track:current\",\"lyric\":\"[00:01.000]line\"}";
        assertSame(value, StockLyricInfo.normalize(value, track));
    }

    @Test
    public void cachedPayloadCanBeReboundToNewGeneration() throws Exception {
        String rebound = StockLyricInfo.rebindGeneration(
                "{\"lyric\":\"[00:01.000]line\",\"sessionGeneration\":1}", 3L);
        assertEquals(3L, new JSONObject(rebound).getLong("sessionGeneration"));
    }

    @Test
    public void trackSnapshotMergesPartialMetadataForSameTrack() {
        StockLyricInfo.TrackSnapshot base = new StockLyricInfo.TrackSnapshot(
                "spotify:track:abc", "Title", "", "Album", 0L);
        StockLyricInfo.TrackSnapshot incoming = new StockLyricInfo.TrackSnapshot(
                "spotify:track:abc", "", "Artist", "", 123_000L);
        StockLyricInfo.TrackSnapshot merged = base.merge(incoming);

        assertEquals("spotify:track:abc", merged.mediaId);
        assertEquals("Title", merged.title);
        assertEquals("Artist", merged.artist);
        assertEquals("Album", merged.album);
        assertEquals(123_000L, merged.durationMs);
        assertTrue(merged.hasQueryIdentity());
    }
}
