# Native playlist integration — VocaColle 7.48.0

Implemented locally on 2026-11-10. Build and transfer checks pass. The patch applies to the
original 7.48.0 XAPK. Emitted-code and APK signature verification are recorded under
`build/native-playlists/`. Device UI behavior is **not verified**: ADB currently lists no device,
and reconnecting to the previously used Waydroid address timed out.

## Intended app behavior

- The original playlist button beside the heart continues to use the original picker.
- The patch's named playlists are copied into real native **LOCALLIST** records. VocaColle's
  Library and original playlist screens then own creation, editing, deletion, and playback.
- The player's **⋮ → Save · Download… → Playlists** action opens that same picker. Download
  and creator shortcuts remain available. If the player host is unavailable, the app explains
  where the original control is. If the native repository is unavailable, the legacy UI remains.
- Favorites, downloads, watched creators, and saved mylists remain in the patch library.
- Original playlist JSON remains available as a backup. Native edits are not exported into
  that old JSON; this limitation is described in English, Korean, and Japanese in the app.

Migration runs on one worker, using the app's coroutine runner and repository. Each native
library owner has a separate durable journal. An existing native list with the same name gets
a separate numbered copy. Resumed transfers deduplicate against native item IDs. Completed
transfers stay completed after native rename/deletion. Later imports and limit saves queue
only newly added backup IDs, preserving native removal of earlier songs. If a previously
transferred list was deleted, a later new save can create a new list containing the new IDs.

Video-ID additions use the app's normal metadata loading and can need connectivity. A failed
addition leaves that list pending; other lists can still transfer. Reopening the app or the
patch's playlist picker retries pending work, with a ten-second throttle. Copying does not
rewrite the old playlist JSON. Account mylists and login behavior are outside this change.

## Implementation and evidence

| Component | Source / resolved 7.48.0 behavior |
|---|---|
| Transfer engine | `core/library/PlaylistTransfer.kt`; journal reservation, retry, collision handling, settled source IDs |
| Android coordinator | `core/library/NativePlaylists.kt`; owner-scoped preferences, one worker, picker host weak reference |
| Patch bridge | `patches/NativePlaylists.kt`, installed by `Library.kt` |
| Native repository | `PlaylistInLibraryRepositoryInterface.kt` → `y8c`; application getter `NicoboxApplication.F()` |
| Create | `y8c.U(String, ofc, boolean, ep2)` with `ofc.b`, verified as **LOCALLIST** |
| Add video | `y8c.T(xj9, long, ep2)` with `xj9$b(String)` / VideoIdSource |
| Coroutine runner | `h21.b(yr2, ni5, int, Object)`, flag 1 for default context, on the worker |
| Existing contents | `y8c.H().s()`; owner-playlist ID `x1b.a`, complete item list `v8c.c`, media ID `z8c.d` |
| Original picker | `lbc.a(is9, kcc, xh5, zh5, zh5, zh5)` with `kcc$a` and **OPTION_MENU** source |
| Player host | Captured immediately after `hs9.e(composer, 0)`; cleared when PlayerFragment is destroyed |

Targets use source files, signatures, enum constants, and printed property labels, rather than
the obfuscated names above. Printed fields follow the StringBuilder argument back to its field
load, since R8 hoists those loads before the labels. The names above document this exact APK.

The pure Kotlin runner covers copying, empty lists, insertion order, duplicate source IDs,
idempotency, native deletion, same-name collisions, interruption after create/add, offline
retry, independent journal instances, failed reservation persistence, pending deletion, and
incremental copies after native edits. It does not execute Room, Android preferences, the
app's coroutine implementation, or Compose. Gradle's regular test tasks currently report
`NO-SOURCE`; the separate runner provides the migration assertions.

Crash recovery between native creation and committing its ID uses the reserved unique title.
Concurrent manual creation of exactly that reserved title during this narrow recovery window
is not covered by the tests.

## Reproduce

From the repository root, using the existing local test signing key:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/home/mattc/N9_lin23/android-sdk

python3 tools/test_playlist_transfer.py
./gradlew bundle check --no-daemon

reseam patch ../apks/7.48.0/vocacolle-7.48.0.xapk \
  --bundle build/reseam/vocacolle-reseam-patches.reseam \
  --trust 6285c75d50add046b07624ac8e7daa250b6c993e3319b570b75fbcb5e4a57c21 \
  --preset all --option 'Translate UI.language=ko' \
  --key build/login-investigation/vocacolle-diagnostics.pk8 \
  --cert build/login-investigation/vocacolle-diagnostics.der \
  --output build/native-playlists/vocacolle-native-playlists.apks

python3 tools/verify_native_playlists.py \
  build/native-playlists/vocacolle-native-playlists.apks \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --output-dir build/native-playlists/verification
```

The verification utility checks all four APK signatures, ZIP integrity, generated operation
interfaces, native creation/addition calls, the actual enum constants, coroutine runner, field
bridges, original picker delegate, repository observer, and player capture/cleanup hooks.
It writes `verification.json`, disassembly evidence, and component signature reports. It is
specific to the supplied 7.48.0 fixture and does not establish runtime correctness.

The initial development build below retained the pre-existing login diagnostics changes in
the working tree. The v0.22.0 release is prepared from a separate playlist-only source snapshot;
login diagnostics and the signature-spoof experiment are excluded from that release.

## Initial development-build verification

- `python3 tools/test_playlist_transfer.py`: PASS, including incremental import after native edits.
- `./gradlew bundle check --no-daemon`: BUILD SUCCESSFUL, final build 34 seconds.
- Reseam patch: applied 11 patches plus 1 dependency; failed 0; output has four APK components.
- `tools/verify_native_playlists.py`: all 13 DEX checks PASS, four APK signatures valid, ZIP checks pass.
- `git diff --check`: PASS.
- Device UI: not verified. `adb devices -l` currently lists no devices.

| Artifact | SHA-256 |
|---|---|
| `build/reseam/vocacolle-reseam-patches.reseam` | `7623c75d03fdaee093d6d0d4ca2f8856e9494f1d19738df9d638e928ced558e5` |
| `build/native-playlists/vocacolle-native-playlists.apks` | `d3d8f158e18d01ecd0de9d8400ea627389dcd7a8521e72a04e6a8544694b484d` |

Input XAPK SHA-256: `96776e47ebeb164c6bb8c10c8705e5311d4c9f33dc4ad0245b91b761e83821c1`.
The APK components use the existing local test certificate, SHA-256
`2b4cf742c4c19b7afeeb0235ffb3e1065e4ee1f296d6f30b9956fac267b22a12`.
The bundle retains this project's existing trusted signer.

These hashes identify the initial development artifacts, not the subsequently versioned
GitHub release assets. Release checks build the playlist-only source with `-PreleaseTag=v0.22.0`
and repeat the migration, patch-application, DEX, and signature checks. GitHub release assets
contain the patch bundle and patch-source index; they do not contain VocaColle APKs or signing keys.

## Remaining phone checks

- Open an existing installation with patch-created named playlists. Confirm copies in the
  original Library and the picker beside the heart, including an empty list and a name collision.
- Add a song through the original button and through **Save · Download… → Playlists**.
- Rename a copied list, remove a song, delete another list, and restart. Confirm edits persist.
- Import one additional song into an old backup list. Confirm only that new ID is added and
  previously removed native items remain removed.
- Interrupt a copy, reopen the app, and confirm retry without duplicated songs.
- Check download and creator shortcuts, plus a second native library owner if available.

The local build is ready for these checks; no phone data migration or successful UI operation
has been observed in this session.
