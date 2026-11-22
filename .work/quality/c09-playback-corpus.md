# C09 synthetic playback corpus v1

Purpose: deterministic, provider-neutral decoder inputs for C09 Media3 renderer/decoder policy characterization. This corpus is test/evidence data only; it does not change production playback policy.

## Provenance

- generator: FFmpeg `7.1.5-0+deb13u1`
- source: FFmpeg `lavfi` `testsrc2`
- content: synthetic video only; no audio, provider locators, headers, credentials, user data or third-party media
- manifest SHA-256: `c0a7c7a9b8f09f5c33b85519d82e2996e1d2cbc62ad8339767f44fa7dc71fc3c`
- content SHA-256: `10c41a6ef0de1834fcf6658d5c8edddf1abb9e52d933553fb3c108220ccb36b5`

`content SHA-256` is calculated over the six non-manifest files sorted lexically. For every file the digest consumes: UTF-8 relative path, one NUL byte, an 8-byte big-endian file length, then the raw file bytes.

## Generator commands

```bash
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=30:duration=1' -an -c:v libx264 -threads 1 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 30 -keyint_min 30 -sc_threshold 0 -bf 0 -b:v 220k -maxrate 220k -bufsize 440k -mpegts_flags +resend_headers -f mpegts avc-360p30.ts

ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1280x720:rate=50:duration=1' -an -c:v libx264 -threads 1 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 -bf 0 -b:v 450k -maxrate 450k -bufsize 900k -mpegts_flags +resend_headers -f mpegts avc-720p50.ts

ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=30:duration=1' -an -c:v libx265 -threads 1 -preset ultrafast -pix_fmt yuv420p -x265-params 'log-level=error:pools=none:frame-threads=1:wpp=0:keyint=30:min-keyint=30:scenecut=0:bframes=0:repeat-headers=1' -b:v 180k -maxrate 180k -bufsize 360k -mpegts_flags +resend_headers -f mpegts hevc-360p30.ts

ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=1280x720:rate=50:duration=1' -an -c:v libx265 -threads 1 -preset ultrafast -pix_fmt yuv420p -x265-params 'log-level=error:pools=none:frame-threads=1:wpp=0:keyint=50:min-keyint=50:scenecut=0:bframes=0:repeat-headers=1' -b:v 380k -maxrate 380k -bufsize 760k -mpegts_flags +resend_headers -f mpegts hevc-720p50.ts
```

The HLS manifests are static one-segment VOD playlists referencing `avc-360p30.ts` and `hevc-360p30.ts` respectively. They are checked in byte-for-byte and covered by the content hash.

## File hashes

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `avc-360p30.ts` | 41924 | `f18f019088523bdd9a8cf9521d316fdfa140c84563336ca15f9cd41bc9c038fd` |
| `avc-720p50.ts` | 65048 | `39759e22e38ce40a6c046e33b1780079d9cb4bd2bec297395731d94b43a5738f` |
| `avc-hls.m3u8` | 117 | `80a2bbcdce538eab68d4e5ef51031ceaab8e5d2c0ef958b6fd2f256d5411c327` |
| `hevc-360p30.ts` | 32148 | `9276b5b278de9dbb90ba1fcdadb6af3748f35d1345ab8339f4de2feac5052142` |
| `hevc-720p50.ts` | 54708 | `8c9855b58fb653d554298c6263edf2b1e608cb411f8913273786873bf06d29dc` |
| `hevc-hls.m3u8` | 118 | `648128b2140128e651ee18bd32824323a6872e341b051f9cbab06e548fec6f78` |

This corpus can establish deterministic parser/decoder callback behavior on the environment where it is run. Emulator results are not evidence of vendor MediaCodec compatibility or weak-TV performance.
