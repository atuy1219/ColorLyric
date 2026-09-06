package com.atuy.colorlyric;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpotifyHeaderStoreTest {
    @Test
    public void becomesReadyOnlyAfterAllRequiredHeadersArrive() {
        assertFalse(SpotifyHeaderStore.isReady());
        assertFalse(SpotifyHeaderStore.ingest("Authorization", "Bearer x"));
        assertFalse(SpotifyHeaderStore.ingest("client-token", "token"));
        assertFalse(SpotifyHeaderStore.ingest("user-agent", "ua"));
        assertTrue(SpotifyHeaderStore.ingest("x-client-id", "client"));
        assertTrue(SpotifyHeaderStore.isReady());

        SpotifyHeaderStore.invalidateAuthorization();
        assertFalse(SpotifyHeaderStore.isReady());
    }
}
