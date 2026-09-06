# ColorLyric

ColorLyric is a Spotify-only Xposed module for ColorOS/OxygenOS lock-screen lyrics.
It hooks `com.spotify.music` only. `com.android.systemui` is not in scope.

## Goal

QQ Music on recent OPlus builds publishes a stock `lyricInfo` object through its MediaSession.
ColorLyric makes Spotify publish the same core schema:

```json
{
  "id": 0,
  "songName": "...",
  "artist": "...",
  "songId": 123,
  "lyricType": 0,
  "lyric": "[00:01.00]...",
  "noLyric": false,
  "transLyric": "",
  "txtLyric": ""
}
```

This is intentionally an app-side experiment. If the stock lyric-page entrance still does not
appear with this contract, the remaining gate is likely package policy inside OPlus SystemUI.

## Lyric source

- If another Spotify provider already published `MediaMetadata["lyricInfo"]`, ColorLyric normalizes
  that payload to the QQ Music stock schema.
- Otherwise ColorLyric obtains synced lyrics from LRCLIB.
- Spotify MediaSession metadata is accumulated across multiple `setMetadata()` calls because Spotify
  may publish track ID, title, album and duration in separate updates.
- With a valid duration, `/api/get` is tried first.
- Without a duration, or when `/api/get` does not produce an exact result, `/api/search` is used.
  Search results are accepted only when title/artist and, when available, album match exactly and
  the candidate is unique. When duration is available it must match within 2 seconds.
- Once lyrics are obtained, cached `lyricInfo` is re-injected into later Spotify metadata updates so
  Spotify cannot accidentally clear it.

No Spotify account token, cookie, private API, or SystemUI hook is used.

## Installation

1. Install ColorLyric.
2. Enable only `com.spotify.music` in LSPosed.
3. Do not add `com.android.systemui` to the scope.
4. Force-stop Spotify and start it again after changing the module or scope.
5. Play a normal `spotify:track:` item.
6. Lock the device and check the media card.

Debug:

```sh
adb logcat -s ColorLyric
```

Useful logs include:

```text
loaded in Spotify main process; provider not required
track=........ generation=1 id=true title=true artist=true album=true duration=0
LRCLIB outcome=search track=........
stock lyricInfo published: ........
cached lyricInfo re-injected: ........
```

If acquisition fails, `LRCLIB outcome=...` reports whether the failure came from an HTTP error,
an ambiguous search, or no exact match.

## Research notes

The QQ Music Android implementation was inspected to determine the stock OPlus metadata contract.
The public `io.github.andrealtb.coloroslyrics.provider.spotify` implementation was consulted for
architecture and compatibility research only. ColorLyric's Spotify hook and LRCLIB path are
independently implemented.

## License

GNU General Public License v3.0 only (`GPL-3.0-only`).
