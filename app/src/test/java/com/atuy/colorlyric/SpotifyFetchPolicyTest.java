package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpotifyFetchPolicyTest {
    @Test
    public void requestKeyIncludesGeneration() {
        assertEquals(
                "spotify:track:abc#7",
                SpotifyFetchPolicy.requestKey("spotify:track:abc", 7L));
    }

    @Test
    public void negativeCacheUsesExpectedTtls() {
        assertEquals(SpotifyFetchPolicy.NOT_FOUND_TTL_MS,
                SpotifyFetchPolicy.negativeTtlMs("http-404"));
        assertEquals(SpotifyFetchPolicy.FORBIDDEN_TTL_MS,
                SpotifyFetchPolicy.negativeTtlMs("http-403"));
        assertEquals(SpotifyFetchPolicy.RATE_LIMIT_TTL_MS,
                SpotifyFetchPolicy.negativeTtlMs("http-429"));
        assertEquals(SpotifyFetchPolicy.NO_LYRICS_TTL_MS,
                SpotifyFetchPolicy.negativeTtlMs("lines-empty"));
        assertEquals(0L, SpotifyFetchPolicy.negativeTtlMs("http-500"));
    }

    @Test
    public void negativeCacheExpires() {
        assertTrue(SpotifyFetchPolicy.isNegativeCached(2_000L, 1_000L));
        assertFalse(SpotifyFetchPolicy.isNegativeCached(1_000L, 1_000L));
        assertFalse(SpotifyFetchPolicy.isNegativeCached(null, 1_000L));
    }
}
