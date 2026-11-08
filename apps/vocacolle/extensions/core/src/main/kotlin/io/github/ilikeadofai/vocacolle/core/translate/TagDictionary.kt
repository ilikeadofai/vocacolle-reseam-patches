package io.github.ilikeadofai.vocacolle.core.translate

/*
 * Reviewed names for the tags VocaColle shows most: voice banks under their official romanized
 * and usual Korean names, and the scene's milestone and genre tags. Used before any translation
 * service, and without one, so these read the same everywhere and never leave the device.
 * Tab-separated: Japanese tag, Korean, English.
 */
private const val ENTRIES = """
VOCALOID	VOCALOID	VOCALOID
ボーカロイド	보컬로이드	Vocaloid
ボカロ	보카로	Vocaloid
初音ミク	하츠네 미쿠	Hatsune Miku
鏡音リン	카가미네 린	Kagamine Rin
鏡音レン	카가미네 렌	Kagamine Len
鏡音リン・レン	카가미네 린·렌	Kagamine Rin & Len
巡音ルカ	메구리네 루카	Megurine Luka
KAITO	KAITO	KAITO
MEIKO	MEIKO	MEIKO
GUMI	GUMI	GUMI
Megpoid	Megpoid	Megpoid
IA	IA	IA
結月ゆかり	유즈키 유카리	Yuzuki Yukari
重音テト	카사네 테토	Kasane Teto
flower	flower	flower
v flower	v flower	v flower
可不	카후	KAFU
星界	세카	SEKAI
裏命	리메	RIME
狐子	코코	COKO
歌愛ユキ	카아이 유키	Kaai Yuki
東北きりたん	토호쿠 키리탄	Tohoku Kiritan
東北ずん子	토호쿠 준코	Tohoku Zunko
ずんだもん	즌다몬	Zundamon
音街ウナ	오토마치 우나	Otomachi Una
知声	치세	Chis-A
足立レイ	아다치 레이	Adachi Rei
弦巻マキ	츠루마키 마키	Tsurumaki Maki
紲星あかり	키즈나 아카리	Kizuna Akari
神威がくぽ	카무이 가쿠포	Kamui Gakupo
心華	신화	Xin Hua
洛天依	뤄톈이	Luo Tianyi
花隈千冬	하나쿠마 치후유	Hanakuma Chifuyu
小春六花	코하루 릿카	Koharu Rikka
夏色花梨	나츠키 카린	Natsuki Karin
初音ミクオリジナル曲	하츠네 미쿠 오리지널 곡	Hatsune Miku original song
VOCALOIDオリジナル曲	VOCALOID 오리지널 곡	VOCALOID original song
ボカロオリジナル曲	보카로 오리지널 곡	Vocaloid original song
ボカロオリジナル	보카로 오리지널	Vocaloid original
合成音声	합성 음성	Synthesized voice
合成音声オリジナル曲	합성 음성 오리지널 곡	Synthesized voice original song
VOCALOID殿堂入り	VOCALOID 전당 입성	VOCALOID Hall of Fame
VOCALOID伝説入り	VOCALOID 전설 입성	VOCALOID Legend
VOCALOID神話入り	VOCALOID 신화 입성	VOCALOID Myth
ミリオン達成	밀리언 달성	Million views
VOCALOID-PV	VOCALOID-PV	VOCALOID-PV
ボカコレ	보카코레	VocaColle
The VOCALOID Collection	The VOCALOID Collection	The VOCALOID Collection
ボカコレ2024夏	보카코레 2024 여름	VocaColle 2024 Summer
ボカコレ2024冬	보카코레 2024 겨울	VocaColle 2024 Winter
ボカコレ2025夏	보카코레 2025 여름	VocaColle 2025 Summer
ボカコレ2025冬	보카코레 2025 겨울	VocaColle 2025 Winter
ボカコレ2026冬	보카코레 2026 겨울	VocaColle 2026 Winter
ボカコレ2026夏	보카코레 2026 여름	VocaColle 2026 Summer
歌ってみた	불러 보았다	Sung a cover
演奏してみた	연주해 보았다	Played a cover
踊ってみた	춰 보았다	Danced a cover
弾いてみた	쳐 보았다	Played a cover
作ってみた	만들어 보았다	Made it
音楽	음악	Music
東方	동방	Touhou
東方アレンジ	동방 어레인지	Touhou arrangement
東方Project	동방 Project	Touhou Project
UTAU	UTAU	UTAU
UTAUオリジナル曲	UTAU 오리지널 곡	UTAU original song
CeVIO	CeVIO	CeVIO
CeVIOオリジナル曲	CeVIO 오리지널 곡	CeVIO original song
SynthesizerV	SynthesizerV	Synthesizer V
SynthV	SynthV	SynthV
NEUTRINO	NEUTRINO	NEUTRINO
MMD	MMD	MMD
MikuMikuDance	MikuMikuDance	MikuMikuDance
プロセカ	프로세카	Project SEKAI
プロジェクトセカイ	프로젝트 세카이	Project SEKAI
マジカルミライ	매지컬 미라이	Magical Mirai
初音ミクシンフォニー	하츠네 미쿠 심포니	Hatsune Miku Symphony
作業用BGM	작업용 BGM	BGM for working
神曲	명곡	Masterpiece
名曲	명곡	Great song
中毒性	중독성	Addictive
泣ける	울리는 곡	Tearjerker
かっこいい	멋있다	Cool
かわいい	귀엽다	Cute
電波ソング	전파 송	Denpa song
ロック	록	Rock
ポップ	팝	Pop
バラード	발라드	Ballad
エレクトロ	일렉트로	Electro
ジャズ	재즈	Jazz
和風	일본풍	Japanese style
和ロック	일본풍 록	Japanese rock
失恋ソング	이별 노래	Breakup song
ラブソング	러브송	Love song
カゲロウプロジェクト	카게로우 프로젝트	Kagerou Project
アレンジ	어레인지	Arrangement
リミックス	리믹스	Remix
カバー	커버	Cover
セルフカバー	셀프 커버	Self cover
オリジナル曲	오리지널 곡	Original song
オリジナル	오리지널	Original
MV	MV	MV
PV	PV	PV
アニメ	애니메이션	Anime
ゲーム	게임	Game
"""

private val DICTIONARY: Map<String, Pair<String, String>> by lazy {
    ENTRIES.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == 3 && parts[0].isNotBlank()) parts[0].trim() to (parts[1].trim() to parts[2].trim()) else null
    }.toMap()
}

/** The reviewed name of [tag] in [language] ("ko" or "en"), or null when it isn't listed. */
internal fun dictionaryTag(tag: String, language: String): String? =
    DICTIONARY[tag.trim()]?.let { if (language == "ko") it.first else it.second }
