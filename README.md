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

### `jp.nicovideo.nicobox` 7.48.0

| Patch | What it does |
|---|---|
| Ad controls | Adds switches to hide app-open ads, banner and in-feed ads, audio ads between tracks, and Premium pop-ups. All four start on, and can be turned off in the Reseam patches screen, opened from its row in VocaColle's Settings. |
| Content translation | Translates Japanese song titles, work descriptions, and notices into Korean or English, with Google Translate (no key) or an OpenAI-compatible API and your own key. Off until turned on in the Reseam patches screen; titles are then sent to the chosen service. |
| Custom branding | Changes the launcher name and icon. With no options set it changes nothing, so the original ボカコレ name and icon stay. The name is a separate resource, so text inside the app that says ボカコレ is not affected. |
| External lyrics | Shows lyrics from LRCLIB, NetEase Cloud Music, KuGou, or VocaDB when VocaColle has none for a song or can't show them, and lets the lyrics button open for those songs. Songs are matched by the exact Niconico video on VocaDB. Off until turned on in the Reseam patches screen. |
| Translate UI | Shows VocaColle in Korean or English: every reviewed UI string, text compiled into the app, and the ranking and push-topic labels the server sends. Titles, creators, tags, and anything sent back to the server stay in the original Japanese. |
| VocaDB song titles | Shows the English or romanized title VocaDB lists for the exact Niconico video in the player. Off until turned on in the Reseam patches screen; when on, the player's video ids are sent to vocadb.net. |
| YouTube share links | When sharing a work, uses the YouTube original that VocaDB lists for the exact Niconico video instead of the Niconico link. Off until turned on in the Reseam patches screen; when on, the shared video's id is sent to vocadb.net. |

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

Selected by default. Once applied, a **Reseam patches** row appears in VocaColle's Settings
list, right after About VocaColle. Its four switches all start on, and each can be turned off there:

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

### YouTube share links

Off by default, with its own switch, also off. When on, sharing a work replaces the Niconico
link in the share text with the YouTube **Original** PV that VocaDB lists for that exact
Niconico video. Videos VocaDB hasn't been asked about yet keep the Niconico link and are looked
up for the next share; the player's VocaDB title lookups fill the same cache.

### Content translation

Off by default, with its own switches, also off:

- **Song titles**, across the app: the media models behind Home, search, rankings, playlists,
  the mini player, the player, and the work detail screen get the translated title for display.
  Titles not translated yet show in Japanese and are translated in the background, up to 16 per
  request, for the next time they appear; translations are cached on the device. Titles VocaDB
  already provides are not sent.
- **Descriptions**, on request: a **Translate** button under a work's description puts its
  translation in place of the original, and **Show original** puts it back. The description is
  sent only when the button is tapped.
- **Notices**: notice titles in Settings are translated, and notice articles open through
  Google Translate's page proxy.

The language is Korean or English: by default the same as the Translate UI patch's, else the
device's. The service is Google Translate's public web endpoint by default (no key), or any
OpenAI-compatible chat completions API with your own key, set from an **API key** row that
stores it encrypted with the Android Keystore and keeps it out of settings exports.

### External lyrics

Off by default, with its own switch, also off. When VocaColle can't show a song's lyrics, for
any reason but being offline, the patch answers with lyrics from other sources: the song is
matched by the exact Niconico video on VocaDB, then the sources are tried in the order the
**Lyrics sources** setting lists (default `lrclib,netease,kugou,vocadb`): LRCLIB, NetEase Cloud
Music, and KuGou for synced lyrics whose recording is within 3 seconds of the song's length,
and VocaDB's original lyrics, untimed. A credit line names the source. For songs with no lyrics
registered, the lyrics button opens once a source has them; each track is looked up when it
starts playing. The official lyrics and their limit are not touched.

### Custom branding

Off by default, and with no options set it changes nothing. `appName` sets the name under the
launcher icon; it is a separate resource, so text inside the app that says ボカコレ stays.
`icon` is a folder with either a square `icon.png`, or `mipmap-mdpi` … `mipmap-xxxhdpi`
folders each holding a square `ic_launcher.png` (48, 72, 96, 144, 192 px). The icon becomes a
plain (non-adaptive) launcher icon on every Android version.

```shell
--enable "Custom branding" --option "Custom branding.appName=보카코레" --option "Custom branding.icon=./my-icon"
```

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
