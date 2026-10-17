package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SpotifyHeaderStoreTest {
    @Before
    public void setUp() {
        SpotifyHeaderStore.clearForTest();
    }

    @After
    public void tearDown() {
        SpotifyHeaderStore.clearForTest();
    }

    @Test
    public void becomesReadyWithBearerWithoutOptionalHeaders() {
        assertFalse(SpotifyHeaderStore.isReady());
        assertFalse(SpotifyHeaderStore.ingest("Authorization", "Basic abc"));
        assertFalse(SpotifyHeaderStore.isReady());
        assertTrue(SpotifyHeaderStore.ingest("Authorization", "Bearer x"));
        assertTrue(SpotifyHeaderStore.isReady());
        assertEquals("Bearer x", SpotifyHeaderStore.snapshot().get("authorization"));
        assertFalse(SpotifyHeaderStore.ingest("Authorization", "Bearer x"));
    }

    @Test
    public void optionalHeadersAndRefreshIncrementRevisionAndTriggerRetry() {
        assertTrue(SpotifyHeaderStore.ingest("Authorization", "Bearer original"));
        long before = SpotifyHeaderStore.revision();
        assertTrue(SpotifyHeaderStore.ingest("client-token", "token"));
        assertTrue(SpotifyHeaderStore.revision() > before);
        long after = SpotifyHeaderStore.revision();
        assertFalse(SpotifyHeaderStore.ingest("client-token", "token"));
        assertEquals(after, SpotifyHeaderStore.revision());
        assertTrue(SpotifyHeaderStore.ingest("Authorization", "Bearer refreshed"));
        assertTrue(SpotifyHeaderStore.revision() > after);
        assertTrue(SpotifyHeaderStore.isReady());

        SpotifyHeaderStore.invalidateAuthorization();
        assertFalse(SpotifyHeaderStore.isReady());
        assertFalse(SpotifyHeaderStore.snapshot().containsKey("client-token"));
        assertTrue(SpotifyHeaderStore.ingest("Authorization", "Bearer resumed"));
        assertTrue(SpotifyHeaderStore.isReady());
    }

    @Test
    public void preservesOptionalHeadersAndIgnoresIrrelevantKeys() {
        assertFalse(SpotifyHeaderStore.ingest("X-Irrelevant", "unused"));
        assertFalse(SpotifyHeaderStore.ingest("x-client-id", "client"));
        assertFalse(SpotifyHeaderStore.ingest("user-agent", "Spotify/Android"));
        assertFalse(SpotifyHeaderStore.isReady());
        assertTrue(SpotifyHeaderStore.ingest("Authorization", "Bearer token"));
        assertEquals("client", SpotifyHeaderStore.snapshot().get("x-client-id"));
        assertEquals("Spotify/Android", SpotifyHeaderStore.snapshot().get("user-agent"));
    }
}
