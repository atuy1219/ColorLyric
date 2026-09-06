# ColorLyric

ColorLyric is a Spotify-only Xposed module for ColorOS/OxygenOS lock-screen lyrics.
It hooks `com.spotify.music` only. `com.android.systemui` is not in scope.

## Goal

QQ Music on OPlus API 37+ publishes a stock `lyricInfo` object through its MediaSession.
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
appear with this exact contract, the remaining gate is very likely package policy inside OPlus
SystemUI and cannot be solved by metadata formatting alone.

## Lyric source

- If another Spotify Provider already published `MediaMetadata["lyricInfo"]`, ColorLyric normalizes
  that payload to the QQ Music stock schema.
- Otherwise ColorLyric queries LRCLIB `/api/get` using Spotify's public MediaSession metadata and
  only accepts an exact title/artist match with duration within 2 seconds.

No Spotify account token, cookie, private API, or SystemUI hook is used.

## Installation

1. Install ColorLyric.
2. Enable only `com.spotify.music` in LSPosed.
3. Do not add `com.android.systemui` to the scope.
4. Force-stop Spotify and start it again.
5. Play a normal `spotify:track:` item with synced lyrics.
6. Lock the device and check the media card for the stock lyric-page entrance.

Debug:

```sh
adb logcat -s ColorLyric
```

Expected logs include:

```text
loaded in Spotify main process
QQ schema applied from existing lyricInfo: ...
```

or, without another Provider:

```text
stock lyricInfo published: ...
```

## Research notes

The QQ Music Android implementation was inspected to determine the stock OPlus metadata contract.
Its `addDataForOplusSeedling` path writes `ratingUri` conditionally and writes the `lyricInfo`
object above; the feature is enabled when `ro.build.version.oplus.api >= 37`.

The public `io.github.andrealtb.coloroslyrics.provider.spotify` implementation was consulted for
architecture and compatibility research only. ColorLyric's hook and LRCLIB path are independently
implemented.

## License

GNU General Public License v3.0 only (`GPL-3.0-only`).
