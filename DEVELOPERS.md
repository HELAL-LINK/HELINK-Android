> `third_party/jniLibs` (yt-dlp, Python and ffmpeg for the phone) is not kept here: `tools/fetch_natives.sh` fetches it. The page itself (README.md), `latest.json` and `assets/` are what HELINK reads and shows.

# HELINK for Android

Video downloader for YouTube, Facebook, Instagram, TikTok and X, for Android 11 and newer
(arm64-v8a and armeabi-v7a). Same design and behaviour as the Windows app "Helal".

## How it works

- `src/com/alihelal/core/` – the engine, plain Java 8 (no Android classes): link recognition,
  quality choices, error messages, progress, yt-dlp updates, music removal. A line-by-line
  port of the Windows version's Go code, tested on a desktop with the same fixtures.
- `src/com/alihelal/app/` – the Android side: one screen (a WebView showing `ui/index.html`),
  a foreground service with a progress notification, saving through MediaStore into
  `Download/HELINK`, and "Share → HELINK" from other apps.
- Downloads list (since 1.1.0, no extra libraries): every download is a `DownloadTask` kept in
  `downloads.json` in the app's private folder (`DownloadStore`, written atomically), with its own
  work folder `work/jobs/<id>`. Two run at once (`Engine.PARALLEL`), the rest wait in a queue
  (first come, first served; a new one starts only once its folder holds what yt-dlp found).
  Pause stops yt-dlp and keeps its `.part` files; resume runs yt-dlp again on the same folder and
  output name, so it continues with HTTP range requests (checked by the `e2eQueue` test). Delete
  stops it and removes the folder. Downloads that were running when the app was closed come back
  paused. The page gets `task` events (`add`/`update`/`remove`, with a revision number so late
  events are ignored); the service keeps running while anything runs or waits.
- Languages (since 1.3.0): Modern Standard Arabic (default) and English, switched from the header.
  The page has its own dictionary (`L` in `ui/index.html`) and flips between right-to-left and
  left-to-right; the engine keeps numbers and codes and writes words only when shown (`I18n`,
  `UserError.title()/message()`, `Text.humanSize`…), so everything already on screen, saved
  downloads included, changes language at once. The choice is saved in `settings.json`.
- Without music (since 1.4.0; since 1.5.0 the same remover as HELINK for Windows): a switch
  under the qualities of videos and MP3s, a "remove the music from this file" button under
  finished downloads, and files of the phone (the system's file picker, or "Share → Remove music
  (HELINK)" for audio/video, `MainActivity.addFiles`). Files of the phone are copied into the
  entry's work folder at once (the permission to read them may not last); each becomes an entry of
  type `sep` (`Engine.addFiles`, `Engine.removeMusic`). `MediaSep` is a port of the Windows
  `nomusic.go`: ffprobe says what the file holds, `planFor` keeps its format (MP3 with its cover
  and tags at the same rate, M4A/OGG/OPUS/FLAC/WAV, video copied as it is with subtitles and
  chapters and only its sound encoded again, MP3 for rare kinds), ffmpeg decodes to 44.1 kHz float
  stereo → `libhelinksep.so separate` → ffmpeg encodes, and a video's new sound is joined with its
  picture (`-c copy`, retried without subtitles, then as MKV). The voices go to a file in the work
  folder when there is room (21 MB a minute), with a checkpoint after every chunk, so pause/resume
  and the app being stopped continue where they were; otherwise they stream into the encoder.
  One removal at a time (it takes no download slot meanwhile); stages `copy`, `preparing`,
  `sepmodel`, `sepprep`, `sepwait`, `separate`, `finish`/`merge`, `save`.
- The music remover (`native/sep`, Go, built by `build.sh` into `lib/<abi>/libhelinksep.so`):
  the voice network Kim_Vocal_2 (MDX-Net by KUIELab, trained by KimberleyJSN, published by UVR)
  run the way UVR runs it (n_fft 7680, 256 frames a chunk, 3072 bins), the same code as HELINK for
  Windows (`mdxnet.go`, `mdxremover.go`…). Its inner loops are NEON assembly written by
  `devtools/gen_neon.py` (arm64) and `devtools/gen_neon32.py` (armeabi-v7a), with llvm-mc encoding
  the instructions (only needed to regenerate them): 6×16 tiles of the matrix products, the 3×3
  convolutions of the first three levels the Winograd way F(4×4, 3×3). `helinksep selftest` checks
  them against plain Go on the phone after setup (if they disagree, `--kernel go` is used). The
  model is not in the APK: the first time, `SepTool` downloads UVR's ONNX file (67 MB), checks its
  SHA-256, converts it with `helinksep convert` into `sep/kim-vocal-2.hlw` (33 MB, fp16) and checks
  that file too; HELINK 1.4's Spleeter files are deleted. Phones with less memory run 128 or 64
  frames a chunk (`MediaSep.dimT`; about 450 MB at 256), and a remover stopped by the system for
  memory is run again with smaller chunks. It runs at `nice 10`. The weight file is read into the
  network and given back to the system before the network takes its working memory.
- Courses and playlists (since 1.6.0, `Playlist.java`, `Engine.runFetchList`/`downloadList`): a
  YouTube playlist link (or "Download the whole list" under a video opened from one; `listUrl` in
  the `info` event, mixes and personal lists left out) is read with `yt-dlp -J --flat-playlist`
  into numbered entries (up to `Playlist.MAX_ENTRIES`, 1000), unavailable ones marked. The page's
  course screen (`#s-course`) picks a kind and one quality for all (`v1080`…`v360`, `mp3-320`…
  `mp3-128`: ceilings, `-f bv*+ba/b -S res:N,vcodec:h264,acodec:aac`, MP3 `ba/b` + quality) and
  the videos; `downloadList` queues one ordinary `DownloadTask` per video, named `NN - title`,
  with `list` (shown on its card) and `subdir`, so it is saved in `Download/HELINK/<list name>`
  (`Host.save(…, subdir, …)`, `RELATIVE_PATH`). Videos already queued with the same quality are
  skipped. "In downloads" marks come from the unfinished downloads, the finished ones whose file
  still exists (`Host.exists`), and `have.json` (`Playlist.Ledger`: video id → kind → saved URI,
  4000 at most), so they survive clearing the list. Channels get the `channel` message, other
  sites' lists `playlist`.
- A part of a video (since 1.7.0, the "Clip" tab; `Engine.clipFetch`/`clipDownload`): its own reading
  of a link (`readPost`, shared with the first tab, with its own seq and `clipCur`; "clip" events).
  `Formats.preview` picks what the page can play: a whole file with picture and sound (360p-720p,
  H.264 first), or picture and sound apart (played together), or sound only; never a stream in
  pieces. The page cannot send a site's headers or cookies, so `PreviewServer` (127.0.0.1, a port
  of its own, long random addresses) fetches the bytes with them and answers the player's range
  requests (`res/xml/network_security_config.xml` allows clear text to 127.0.0.1 only). The page
  picks the part on a timeline (two handles, -/+ a second, "start/end here" at the playing
  moment, "play the part" stops at its end). The download is an ordinary `DownloadTask` with
  `clipStart`/`clipEnd`: `--download-sections *a-b --force-keyframes-at-cuts` (ffmpeg reads only
  the part; the picture is encoded again at the cuts, `-preset veryfast -crf 21`), its progress
  from ffmpeg's `time=`, its name "… - مقطع 1.20-2.05 - 720p".
- WhatsApp statuses (since 1.7.0, the "Statuses" tab; `WaStatuses`, Android only): WhatsApp keeps
  the statuses viewed in `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` (and
  `com.whatsapp.w4b/WhatsApp Business`). `ACTION_OPEN_DOCUMENT_TREE` opens the picker at it
  (`EXTRA_INITIAL_URI`); the folder picked is checked and kept (`takePersistableUriPermission`).
  The statuses are listed with `DocumentsContract`, shown through `PreviewServer` (the file in
  ranges, a small JPEG made once with `BitmapFactory`/`MediaMetadataRetriever`, cached), and saved
  with the media store in `Download/HELINK/WhatsApp` (what was saved is remembered, and checked
  still there). `<queries>` names the two WhatsApp packages.
- Updates (since 1.6.0, `Updates.java`, `UpdateInstaller`, `UpdateReceiver`): HELINK reads
  `latest.json` from github.com/HELAL-LINK/HELINK-Android (raw.githubusercontent.com, then
  jsDelivr), 8 s after it starts, when its screen shows or the internet comes back
  (`registerDefaultNetworkCallback`), at most every 6 hours (2 minutes while offline). The file is
  `{"manifest": base64(JSON), "sig": base64(Ed25519)}`, checked with the public key in
  `Updates.publicKey` (`Ed25519.java`, plain BigInteger RFC 8032: Android 11 has no Ed25519 of its
  own); the manifest names the app (`helink-android`), the version and versionCode, the notes
  (ar/en) and the APK's addresses, size and SHA-256. A newer version is offered once per version in
  a sheet ("Update now" / "Not now"; "Not now" waits a day, the header's Update button and About
  stay). "Update now" downloads the APK into `files/update` (`Http.fetchVerified`: resumes, checks
  size and SHA-256), notes the running downloads, and hands it to `PackageInstaller` (a session;
  `REQUEST_INSTALL_PACKAGES`, the first time the user allows "install unknown apps" in Settings;
  `USER_ACTION_NOT_REQUIRED` from Android 12 once HELINK installed itself). Android asks to confirm
  (`STATUS_PENDING_USER_ACTION` → its window, shown again when the screen comes back), keeps the
  data and replaces the app; `MY_PACKAGE_REPLACED` starts the downloads again and posts "HELINK was
  updated". Phones only take an APK signed with the same key (`keystore/`), so every release must
  be signed with it. The same update key signs HELINK for Windows' updates.
- The About screen's photo is `ui/owner.jpg`, embedded into the page by `tools/gen_html.py`. The HELINK
  logo is drawn inline in `ui/index.html` (traced from the brand artwork; its H is the launcher icon).
  The app was called "ALI HELAL" up to 1.1.0; the package name `com.alihelal.app` stays, so it updates in place.
- Python 3.12, FFmpeg and QuickJS come prebuilt from youtubedl-android 0.18.1. The small
  programs ship in the APK as `lib*.so` files (Android only lets apps run programs installed
  with the APK); their libraries (about 50 MB per phone type) are downloaded on first start
  from youtubedl-android's repository at a pinned commit, checked against SHA-256 sums
  (`assets/runtime.json`, written by `build.sh`), and unzipped. `FULL=1 ./build.sh` puts them
  inside the APK instead (about 95 MB, no download). yt-dlp ships in `assets/ytdlp.zip` and is
  replaced by newer nightly builds (checked every 3 days, or right away when a site breaks;
  verified with the published SHA-256 sums, and only used if it starts correctly).

## Build

Requirements: JDK, Python 3, Go 1.24+, `aapt2`, `dalvik-exchange` (dx), `zipalign`, `apksigner`
(Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner`), plus two `android.jar`
files (API 35 to compile, API 33 to link resources), from e.g. github.com/Sable/android-platforms.

```
tools/fetch_natives.sh            # once: Python/FFmpeg/QuickJS into third_party/jniLibs
ANDROID_JAR=... ANDROID_RES_JAR=... KEYSTORE=... KEY_PASS=... ./build.sh
```

The APK is written to `build/HELINK.apk`. Raise `VERSION_CODE`/`VERSION_NAME` for each
release and sign with the same key, otherwise phones refuse the update.

## Publishing a version (and its update)

```
# raise VERSION_CODE and VERSION_NAME in build.sh (and Engine.VERSION, the About text's default),
# write docs/release-notes/<version>.ar.txt and .en.txt (one line each thing new), then:
./build.sh
tools/publish_update.sh <clone of github.com/HELAL-LINK/HELINK-Android> [update key]
```

`tools/publish_update.sh` checks that `build/HELINK.apk` is that version and signed with HELINK's
certificate (SHA-256 `1a85dbe8…`), and that the update key is the one in `Updates.java`, then makes
two commits on `main`: the APK as `HELINK-<version>.apk`, then `latest.json` signed by
`tools/updsign` (naming the APK at the first commit, on GitHub and jsDelivr, so its addresses never
change) with the download page (`README.md` from `tools/download-page.md`, pictures from
`docs/screenshots/`), and pushes them together. The update key (`update-ed25519.key`, an Ed25519
seed in base64) is kept outside the project, never in a repository or a package. Phones on 1.5.0 or
older have no updater: they install 1.6.0 by hand once.

To bundle a newer yt-dlp: `pip install -U --pre "yt-dlp[default]"`, then
`python3 tools/make_ytdlp_zip.py <site-packages>/yt_dlp <yt_dlp_ejs wheel> assets/ytdlp.zip`.

## Tests

```
tools/test.sh                     # all; or e.g. tools/test.sh parseLink e2eDownload
```

`e2eNoMusic` needs UVR's model: `HELINK_SEP_ARCHIVES=<folder with Kim_Vocal_2.onnx>`; `tools/test.sh`
builds the remover for the desktop into `build/sep/helinksep`. The remover's own tests are in
`native/sep` (`go test`; with `HELINK_SEP_ARCHIVES` and `HELINK_MDX_REF` they compare the network
with ONNX Runtime and the whole pipeline with UVR's way, and check that a run resumed from a
checkpoint gives exactly the same). For the phones' code, run the test binary under qemu:
`GOARCH=arm64 go test -c -o t && qemu-aarch64 ./t -test.run 'Kernels|Resume'` (and `GOARCH=arm
GOARM=7 … qemu-arm -cpu cortex-a7`); the networks were checked against ONNX Runtime this way
(arm64 119.5 dB, armeabi-v7a 119.3 dB, the same as on x86). The APK's two `libhelinksep.so` were
also run under `qemu-* -strace` (selftest, convert, separate with a checkpoint): every system call
they make is in bionic's app seccomp lists (Android 11 and current), `convert` writes the same file
as on x86 (SHA-256 d0b06e51…), and `separate` gives the x86 result within 87 dB.

End-to-end tests run the real yt-dlp (with desktop Python and ffmpeg) against a local server
(`/home/claude/ytest/media`, made by `test/makeinfo.py`). The UI can be previewed in a desktop
browser: `python3 tools/gen_html.py ui third_party/fonts assets/index.html preview.html data.json`
with `ui/mock.js` (see the usage line at its top).
