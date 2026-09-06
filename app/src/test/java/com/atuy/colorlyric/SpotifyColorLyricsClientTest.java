package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpotifyColorLyricsClientTest {
    @Test
    public void decodesLineSyncedLyrics() {
        String body = "{\"lyrics\":{\"syncType\":\"LINE_SYNCED\",\"provider\":\"spotify\","
                + "\"lines\":[{\"startTimeMs\":\"1234\",\"endTimeMs\":\"2500\","
                + "\"words\":\"hello\"}]}}";

        SpotifyColorLyricsClient.Result result = SpotifyColorLyricsClient.decode(body);
        assertTrue(result.isSuccess());
        assertEquals("LINE_SYNCED", result.syncType);
        assertEquals("spotify", result.source);
        assertEquals("[00:01.234]hello\n", result.lineLyric);
        assertNull(result.rawLyric);
    }

    @Test
    public void decodesSyllableTiming() {
        String body = "{\"lyrics\":{\"syncType\":\"SYLLABLE_SYNCED\",\"lines\":["
                + "{\"startTimeMs\":1000,\"endTimeMs\":3000,\"words\":\"hello\","
                + "\"syllables\":[{\"startTimeMs\":1000,\"chars\":\"he\"},"
                + "{\"startTimeMs\":1800,\"chars\":\"llo\"}]}]}}";

        SpotifyColorLyricsClient.Result result = SpotifyColorLyricsClient.decode(body);
        assertTrue(result.isSuccess());
        assertNotNull(result.rawLyric);
        assertTrue(result.rawLyric.contains("<00:01.000>he"));
        assertTrue(result.rawLyric.contains("<00:01.800>llo"));
        assertTrue(result.rawLyric.contains("<00:03.000>"));
    }

    @Test
    public void rejectsResponseWithoutLines() {
        SpotifyColorLyricsClient.Result result = SpotifyColorLyricsClient.decode(
                "{\"lyrics\":{\"lines\":[]}}");
        assertEquals("lines-empty", result.outcome);
        assertTrue(!result.isSuccess());
    }
}
