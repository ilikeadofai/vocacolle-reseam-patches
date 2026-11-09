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
| Clear caches | Adds a Clear caches row to the Reseam patches screen: VocaDB titles and links, translations and readings, and the lyrics found this session, each with its size, cleared one at a time or all together. Favorites and playlists are kept. |
| Content translation | Translates Japanese song titles, tags, work descriptions, lyrics, and notices into Korean or English, with Google Translate (no key) or an OpenAI-compatible API and your own key. Off until turned on in the Reseam patches screen; titles are then sent to the chosen service. |
| Diagnostics | Adds a Diagnostics row to the Reseam patches screen: whether the app keeps VocaColle's original signature (login and guest login need it for App Check), the Play services version, the device clock against the server's, the last login and guest login error, and whether the server offered high-quality audio for the last track. Changes nothing. |
| Custom branding | Changes the launcher name and icon. With no options set it changes nothing, so the original ボカコレ name and icon stay. The name is a separate resource, so text inside the app that says ボカコレ is not affected. |
| External lyrics | Shows lyrics from LRCLIB, NetEase Cloud Music, KuGou, or VocaDB when VocaColle has none for a song or can't show them, and lets the lyrics button open for those songs. Songs are matched by the exact Niconico video on VocaDB. Off until turned on in the Reseam patches screen. |
| Full rankings | Shows every page of a ranking, up to 300 songs, instead of only the top 100. The extra pages come from VocaColle's own ranking service. Can be turned off in the Reseam patches screen. |
| Local favorites | Adds a library kept on this device only, with no limit: favorites, playlists, watched creators, and saved mylists, with ☆ Favorite and + Playlist buttons on a work's detail screen. When the server refuses a like, mylist item, or follow for the account's limit, the item is kept there instead. The account's likes, mylists, and follows don't change. |
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
- **Tags**: the tag chips on a work's detail screen and the popular and featured tag tiles in
  Search show translated names, redrawn as each translation arrives. About a hundred common tags
  (voice banks, milestones such as VOCALOID殿堂入り, genres) come from a reviewed built-in list,
  with no request and the usual spelling of each name; others go to the chosen service. Only the text drawn on the
  chip changes: tapping a tag still searches the original Japanese tag, and search history keeps it.
- **Descriptions**, on request: a **Translate** button under a work's description puts its
  translation in place of the original, and **Show original** puts it back. The description is
  sent only when the button is tapped.
- **Lyrics**, as extra rows: **Lyrics reading** adds the reading under each Japanese line
  (always from Google Translate), in romaji or in Hangul as Koreans read Japanese (**Reading
  script**; Auto uses Hangul when translating into Korean, and Latin words of the line stay), and **Lyrics translation** adds the translation, with the
  chosen service. They work on any lyrics the player shows, VocaColle's own or the External
  lyrics patch's, and highlight and seek with their line. The lines on screen are sent, 40 per
  request, and the layers appear as they arrive; the lyrics the app loads and caches don't change.
- **Notices**: notice titles in Settings are translated, and notice articles open through
  Google Translate's page proxy.

The language is Korean or English: by default the same as the Translate UI patch's, else the
device's. The service is Google Translate's public web endpoint by default (no key), or any
OpenAI-compatible chat completions API with your own key, set from an **API key** row that
stores it encrypted with the Android Keystore and keeps it out of settings exports.

### External lyrics

Off by default, with its own switch, also off. When VocaColle can't show a song's lyrics, for
any reason but a bad request (offline, saved lyrics only), the patch answers with lyrics from other sources: the song is
matched by the exact Niconico video on VocaDB, then the sources are tried in the order the
**Lyrics sources** setting lists (default `lrclib,netease,kugou,vocadb`): LRCLIB, NetEase Cloud
Music, and KuGou for synced lyrics whose recording is within 3 seconds of the song's length,
and VocaDB's original lyrics, untimed. A credit line names the source. For songs with no lyrics
registered, the lyrics button opens once a source has them; each track is looked up when it
starts playing. Found lyrics are saved on the device for 30 days ("none found" for a day, while
the source order is the same), so they also show offline, and Clear caches removes them. Songs
in VocaColle's offline cache are looked up as soon as the app lists them, and their lyrics are
kept without expiry, with the reading and translation rows fetched ahead when those are on. The official lyrics and their limit are not touched.

### Full rankings

Selected by default, with its switch on. VocaColle's ranking screen asks the ranking service
for page 1 of 100 and stops there, though the service serves three pages and says so. This
patch asks for pages 2 and 3 the same way the app asked for page 1, with the same session and
filters, and appends them, dropping a video that moved between pages so it shows once. A page
that fails ends the list at the pages that loaded. Home's ranking sections are left as they are.

### Local favorites

Selected by default. VocaColle's likes, mylists, and follows live on the server, with the
account's limits. This patch adds a library kept only on the device, with no limit:

- Under a work's description, **☆ Favorite** adds it to the favorites, and **+ Playlist** opens
  a chooser that puts it in any playlist, marked ✓ where it already is, or a new one, and that
  can **watch the work's creator**. The buttons can be hidden with their switch.
- **Favorites and playlists on this device**, in the Reseam patches screen, lists the
  favorites, each playlist, the **creators** you watch, and **saved mylists**. Play a list from
  any song in VocaColle's own playlist player, remove songs, rename or delete playlists; open a
  creator's profile or a saved mylist in the app, or play **Latest from creators**: the ten
  newest uploads of each watched creator, newest first, fetched from nicovideo.jp.
- **Keep refused items on this device** (on by default): when the server refuses a like for the
  daily limit, a mylist item or a mylist follow for the account's limit, or a user follow for the
  follow limit, VocaColle still shows its own error, and the item is kept in the favorites, a
  playlist named after the mylist, the saved mylists, or the creators, with a toast saying so.
- **Export / import** copies the favorites and every playlist as JSON text, or adds the songs
  and playlists of a pasted export that aren't there yet.

The account's likes, mylists, and follows never change, and the library isn't synced to other
devices. It is removed with the app's data.

### Diagnostics

Selected by default. **Diagnostics** in the Reseam patches screen shows, with a copy button:

- **Signature**: whether the installed app still has VocaColle's own signing certificate. A
  re-signed app can't get a Firebase App Check (Play Integrity) token, which is why login and
  guest login fail with WL-E08; no patch can change that, so the fix is to install with the
  original signature kept (CorePatch on a rooted device) or a Reseam mount build.
- **Google Play services** version, and the **device clock** against nicovideo.jp's.
- The **last login error** code (WL-E…) and **last guest login error** type the app recorded.
- **Last track's audio**: whether the server offered high quality to this account. It is only
  reported: what the server offers decides it, and nothing is unlocked.

### Clear caches

Selected by default. The patches keep what they look up so they don't ask again: VocaDB
answers (7 days, 6 hours for "not found") and translations and readings on disk, and lyrics
in memory for the session. **Clear caches** in the Reseam patches screen shows each with its
size and clears one, or all of them; the next look-up then asks again. Favorites and
playlists are not a cache and are never cleared.

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
