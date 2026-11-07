# VocaColle Reseam Patches

Unofficial [Reseam](https://reseam.app) patches for the Android app VocaColle
(`jp.nicovideo.nicobox`): a complete Korean or English UI, and opt-in switches for
ads and Premium pop-ups.

> [!NOTE]
> This project is not affiliated with Reseam or DWANGO. It does not distribute
> VocaColle APKs, signing keys, or account credentials. It replaces
> [vocacolle-morphe-patches](https://github.com/ilikeadofai/vocacolle-morphe-patches),
> which targeted Morphe and VocaColle 7.40.0.

## Install

1. Open the share link from the [latest release](https://github.com/ilikeadofai/vocacolle-reseam-patches/releases/latest)
   on Android, or paste this repository's address into **Add patch source** in Reseam Manager.
2. Check that the signer key Reseam Manager shows matches this one:

   ```text
   6285c75d50add046b07624ac8e7daa250b6c993e3319b570b75fbcb5e4a57c21
   ```

3. Patch VocaColle **7.48.0**. The APKPure XAPK (base plus language, density, and ABI splits)
   works as it is; Reseam patches and signs every split together.

## Patches

<!-- patches:start -->
<!-- patches:end -->

### Translate UI

Selected by default. The `language` option picks Korean (`ko`, the default) or English (`en`).

- **All 1,614 reviewed UI strings** are rewritten in the app's resource table while patching.
  Every screen, dialog, notification, and XML layout reads them the normal way, so nothing
  translates at run time and nothing is missed because a view bypasses a hook.
- Each row of the catalog records the Japanese text 7.48.0 ships. A string whose Japanese
  changed keeps its new Japanese instead of showing a stale translation.
- Japanese literals compiled into the code (the external-link dialog, ad landing pages, the
  Project SEKAI card) are replaced in the bytecode.
- Ranking genre tabs and push-topic titles come from the server; they are translated only at
  display time, for exact known values, so requests and caches keep the original Japanese.
- Song titles, creator names, tags, and text sent to the server (report forms, share hashtags)
  stay in Japanese.

The language is fixed when patching. To switch, patch again with the other value.

### Ad controls

Selected by default. Once applied, a **Reseam patches** item appears in the menu of VocaColle's
Settings screen. Its four switches all start on, and each can be turned off there:

| Switch | What it does |
|---|---|
| Hide app-open ads | Answers "not allowed" when the app asks whether to show its full-screen ad. |
| Hide banner and in-feed ads | Stops the display-ad controller before it loads, and reports "no ad" so the slot collapses. |
| Skip audio ads | Lets the ad source lookup finish, then swaps its answer for "no ad". |
| Hide Premium pop-ups | Skips the seven Premium-only feature pop-ups, the launch-time Premium sheet, and the high-quality audio snackbar. |

Premium registration, subscription management, and every entitlement check stay untouched.

### VocaDB song titles

Off by default, with its own switch, also off, in the Reseam patches screen. When on, each
player item's title is replaced, for display only, with the English (or else romanized) title
VocaDB lists for that **exact** Niconico video: the response must contain an active Niconico PV
with the same id. The video ids of tracks in the player are sent to `vocadb.net`. Results are
cached on the device for 7 days (6 hours for "not on VocaDB"), and a network failure never
counts as "not found". A title that isn't cached yet appears from the player's next refresh,
such as the next track change.

## Compatibility

- Target: VocaColle 7.48.0 (`versionCode 187`). Other versions are skipped unless you run
  them anyway (`--ignore-versions` on the CLI); targets are found by strings, structure, and
  resource ids, so they fail loudly rather than patching the wrong code.
- Reseam engine 0.39.x.

## Build and test

You need JDK 17+, the Android SDK in `ANDROID_HOME`, and the `reseam` CLI matching the plugin
version in `settings.gradle.kts`.

```shell
./gradlew bundle
reseam patch vocacolle-7.48.0.xapk \
  --bundle build/reseam/vocacolle-reseam-patches.reseam \
  --trust 6285c75d50add046b07624ac8e7daa250b6c993e3319b570b75fbcb5e4a57c21 \
  --preset all --option "Translate UI.language=ko" \
  --output vocacolle-patched.apks
```

Install every APK of the output together (`adb install-multiple`), signed with the same key.

The translation catalog is `apps/vocacolle/patch/src/main/resources/vocacolle/strings.tsv`:
one row per string resource, with the 7.48.0 Japanese source, Korean, and English. Escapes are
`\n`, `\t`, and `\\`.

## Release

Push a tag starting with `v`. The workflow builds and signs the bundle with the
`BUNDLE_SIGNING_KEY_B64` secret, and publishes the `.reseam` file and `patches.json` on the
release.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).

## License

[GNU Affero General Public License v3.0](LICENSE), as the Reseam template and patch API are.
