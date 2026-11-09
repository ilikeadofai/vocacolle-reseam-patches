# VocaColle Reseam Patches

Unofficial [Reseam](https://reseam.app) patches for VocaColle (`jp.nicovideo.nicobox`) 7.48.0:
VocaColle in Korean or English, no ads, translated titles and lyrics, lyrics for more songs, and
a library on your phone with one-tap offline downloads.

> [!NOTE]
> Not affiliated with Reseam or DWANGO. This project ships no VocaColle APKs, keys, or accounts.

## Install

1. In Reseam Manager, add this repository as a patch source, or open the share link from the
   [latest release](https://github.com/ilikeadofai/vocacolle-reseam-patches/releases/latest).
   Check that the signer key it shows is
   `6285c75d50add046b07624ac8e7daa250b6c993e3319b570b75fbcb5e4a57c21`.
2. Patch VocaColle **7.48.0** (the APKPure XAPK works as it is). The default selection is
   everything but *App name and icon*; pick Korean or English under *Translate UI*.
3. Open VocaColle → **Settings**. Two rows are added under *About VocaColle*:
   **Reseam patches** (every switch) and **My library (this phone)**.

> [!IMPORTANT]
> Login and guest login need VocaColle's original signature (Firebase App Check). A re-signed
> install fails with WL-E08. Keep the signature (for example CorePatch on a rooted phone) or use
> a Reseam mount build. *Diagnostics* in the Reseam patches screen tells you which case you're in.

## What you get

| Patch | In the app | Starts |
|---|---|---|
| **Translate UI** | The whole app in Korean or English | On |
| **Hide ads** | Reseam patches → Ads: launch, banner/list, audio ads, Premium pop-ups | On |
| **My library** | Settings → My library; ☆ Favorite and ＋ Save under each song's description | On |
| **Full rankings** | Rankings show up to 300 songs | On |
| **Lyrics from other sources** | Reseam patches → Lyrics | Switch off |
| **Translate songs, tags, and lyrics** | Reseam patches → Translation, and → Lyrics → Under each line | Switches off |
| **VocaDB song titles**, **YouTube share links** | Reseam patches → Song info and rankings | Switches off |
| **Clear caches**, **Diagnostics** | Reseam patches → Storage and diagnostics | On |
| **App name and icon** | Launcher name and icon, from patch options | Not selected |

Features that send anything to another service start with their switch off, and each switch
says what it sends. Turning a switch off puts that part of the app back the way it was.

## My library

Everything here is kept on the phone, with no limit, and never changes your account.

- **☆ Favorite** under a song's description adds it to the favorites.
- **＋ Save** opens a list: **⬇ Download for offline**, your playlists (✓ where the song is), a
  new playlist, and **Watch creator**.
- **Download for offline** uses VocaColle's own cache, which the app otherwise offers only for
  whole playlists: progress shows in VocaColle's notification, the cache settings (Wi-Fi only,
  size limit) apply, and *Delete cached content* removes downloads too. You need to be logged in.
- **My library (this phone)** in Settings opens the favorites, playlists, **Downloads**,
  **Creators** (open a profile, or play *Latest from creators*: their newest uploads), and
  **Saved mylists**. Lists play in VocaColle's own player, from any song.
- When your account's limit refuses a like, a mylist item, or a follow, VocaColle still shows
  its error, and the item is kept in My library instead (*Keep what the account limit refuses*).
- **Export / import** copies favorites and playlists as text, to move them to another phone.

## Translation and lyrics

- **Translation** page: song titles everywhere, tags (common ones from a built-in list), a
  Translate button for descriptions, and notices. The service is Google Translate (no key) or an
  OpenAI-compatible API with your key, stored encrypted on the phone.
- **Lyrics** page: lyrics from LRCLIB, NetEase, KuGou, or VocaDB when VocaColle has none or hit
  its limit, matched to the exact video and its length, and kept for offline songs. Under each
  line: the **reading** in romaji or Hangul, and a **translation**.

- **Search in Korean or English** (Translation page): type 千本桜 as *senbonzakura*,
  *thousand cherry blossoms*, or *천본앵*, or 初音ミク as *hatsune miku* or *하츠네 미쿠*. Song,
  producer, and singer names are matched exactly on VocaDB; other Korean, and English phrases of
  two or more words, are translated (*슬픈 노래* → 悲しい歌). A single English word VocaDB
  doesn't know, such as *remix* or *MMD*, is searched as typed. A toast shows what was searched,
  and the search box and history keep what you typed.

Song titles and anything VocaColle sends to its server stay in Japanese; translation is for
display only, and tapping a translated tag still searches the Japanese tag. The one exception is
Korean or English search, whose whole point is to send the Japanese for what you typed.

## Privacy

| Switch | Sends | To |
|---|---|---|
| Translation (titles, tags, notices, lyrics lines) | The Japanese text | Google Translate or your API |
| Description button | The description, when tapped | Google Translate or your API |
| Search in Korean or English | What you search for, if it isn't Japanese | VocaDB, Google Translate |
| Lyrics from other sources | Song name, artist, length | VocaDB, LRCLIB, NetEase, KuGou |
| VocaDB titles, YouTube share links | The video id | vocadb.net |
| Latest from creators | The creators' user ids | nicovideo.jp |
| Diagnostics (when opened) | A request for the server's time | nicovideo.jp |

Nothing else leaves the phone. Hide ads, My library, downloads, and Full rankings use only what
VocaColle itself already talks to.

## All patches

Generated from the bundle on each release.

<!-- patches:start -->

### `jp.nicovideo.nicobox` 7.48.0

| Patch | What it does |
|---|---|
| App name and icon | Changes the launcher name and icon, from the appName and icon options. Changes nothing without them. |
| Clear caches | Adds a button to clear the lyrics, translations, and VocaDB answers the patches keep. |
| Diagnostics | Shows why login, guest login, or high-quality audio don't work. Changes nothing. |
| Full rankings | Shows up to 300 songs in a ranking instead of the top 100. |
| Hide ads | Hides launch, banner, list, and audio ads and Premium pop-ups. Each can be turned back on in the Reseam patches screen. |
| Lyrics from other sources | Shows lyrics from LRCLIB, NetEase, KuGou, or VocaDB when VocaColle has none or hit its limit, and keeps them for offline songs. Off until you turn it on in the app. |
| My library | Adds favorites, playlists, one-tap offline downloads, watched creators, and saved mylists on this phone, with buttons on the song page and a row in VocaColle's Settings. Keeps what your account's limits refuse. |
| Translate songs, tags, and lyrics | Translates song titles, tags, descriptions, notices, and lyrics into Korean or English, with Google Translate or your own AI key. Off until you turn each part on in the app. |
| Translate UI | Shows VocaColle in Korean or English (the language option). Song titles and anything sent to the server stay in Japanese. |
| VocaDB song titles | Shows VocaDB's English or romanized titles in the player. Off until you turn it on in the app. |
| YouTube share links | Shares the YouTube original instead of the Niconico link. Off until you turn it on in the app. |

<!-- patches:end -->

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

See [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/PLAN-P2-P4.md](docs/PLAN-P2-P4.md).

## License

[GNU Affero General Public License v3.0](LICENSE), as the Reseam template and patch API are.
