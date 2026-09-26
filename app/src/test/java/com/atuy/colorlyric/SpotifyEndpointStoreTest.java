package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.List;

public class SpotifyEndpointStoreTest {
    @After
    public void clear() {
        SpotifyEndpointStore.clearForTest();
    }

    @Test
    public void learnsEndpointVersionHostAndQueryFromSpotifyUrl() {
        assertTrue(SpotifyEndpointStore.observeUrl(
                "https://gew1-spclient.spotify.com/color-lyrics/v9/track/oldTrack"
                        + "?vocalRemoval=false&clientLanguage=ja-JP&preview=false&future=1"));

        List<String> urls = SpotifyEndpointStore.candidateUrls("newTrack", "en-US");
        assertTrue(urls.get(0).startsWith(
                "https://gew1-spclient.spotify.com/color-lyrics/v9/track/newTrack?"));
        assertTrue(urls.get(0).contains("future=1"));
        assertEquals(3, urls.size());
    }

    @Test
    public void rejectsNonSpotifyAndNonLyricsUrls() {
        assertFalse(SpotifyEndpointStore.observeUrl(
                "https://example.com/color-lyrics/v9/track/abc"));
        assertFalse(SpotifyEndpointStore.observeUrl(
                "https://guc3-spclient.spotify.com/metadata/v1/track/abc"));
        assertEquals(0, SpotifyEndpointStore.observedCount());
    }

    @Test
    public void revisionChangesOnlyWhenNewTemplateAppears() {
        assertEquals(0L, SpotifyEndpointStore.revision());
        assertTrue(SpotifyEndpointStore.observeUrl(
                "https://guc3-spclient.spotify.com/color-lyrics/v4/track/a?x=1"));
        long first = SpotifyEndpointStore.revision();
        assertFalse(SpotifyEndpointStore.observeUrl(
                "https://guc3-spclient.spotify.com/color-lyrics/v4/track/b?x=1"));
        assertEquals(first, SpotifyEndpointStore.revision());
        assertTrue(SpotifyEndpointStore.observeUrl(
                "https://guc3-spclient.spotify.com/color-lyrics/v5/track/b?x=2"));
        assertTrue(SpotifyEndpointStore.revision() > first);
    }
}
