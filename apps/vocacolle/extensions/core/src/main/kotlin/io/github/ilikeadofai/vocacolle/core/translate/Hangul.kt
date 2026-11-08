package io.github.ilikeadofai.vocacolle.core.translate

import java.text.Normalizer

/*
 * Japanese readings in Hangul, from Google's Hepburn romaji: "Kyozō ni nureta watashi" becomes
 * "쿄조 니 누레타 와타시". Spelled as Korean readers say Japanese, with ん as ㄴ and っ as ㅅ under
 * the syllable before. A word that isn't Japanese romaji, such as English in the lyrics, stays.
 */

private const val SYLLABLE_BASE = 0xAC00
private const val FINAL_N = 4
private const val FINAL_S = 19

// Initial consonant and vowel indexes of Hangul syllables.
private const val G = 0; private const val N = 2; private const val D = 3
private const val R = 5; private const val M = 6; private const val B = 7; private const val S = 9
private const val O = 11; private const val J = 12; private const val CH = 14; private const val K = 15
private const val T = 16; private const val P = 17; private const val H = 18

private const val A = 0; private const val E = 5; private const val YA = 2; private const val YE = 7
private const val OO = 8; private const val WA = 9; private const val YO = 12; private const val U = 13
private const val WE = 15; private const val WI = 16; private const val YU = 17
private const val I = 20

private fun syllable(initial: Int, vowel: Int) = (SYLLABLE_BASE + (initial * 21 + vowel) * 28).toChar()

private val VOWELS = mapOf('a' to A, 'i' to I, 'u' to U, 'e' to E, 'o' to OO)

/** Romaji syllables, longest first, to Hangul. */
private val TABLE: List<Pair<String, String>> by lazy {
    val table = HashMap<String, String>()
    fun row(prefix: String, initial: Int, vararg except: Pair<Char, String>) {
        val overrides = except.toMap()
        for ((v, index) in VOWELS) table[prefix + v] = overrides[v] ?: syllable(initial, index).toString()
    }
    fun yRow(prefix: String, initial: Int) {
        table[prefix + "a"] = syllable(initial, YA).toString()
        table[prefix + "u"] = syllable(initial, YU).toString()
        table[prefix + "o"] = syllable(initial, YO).toString()
        table[prefix + "e"] = syllable(initial, YE).toString()
    }
    row("", O)
    row("k", K); row("g", G); row("s", S, 'i' to "시", 'u' to "스"); row("z", J, 'u' to "즈"); row("t", T, 'i' to "치", 'u' to "츠")
    row("d", D, 'i' to "지", 'u' to "즈"); row("n", N); row("h", H, 'u' to "후"); row("b", B); row("p", P)
    row("m", M); row("r", R); row("f", P, 'u' to "후"); row("v", B); row("j", J); row("ch", CH); row("sh", S)
    row("ts", CH, 'u' to "츠")
    table["ya"] = syllable(O, YA).toString(); table["yu"] = syllable(O, YU).toString()
    table["yo"] = syllable(O, YO).toString(); table["ye"] = syllable(O, YE).toString()
    table["wa"] = syllable(O, WA).toString(); table["wo"] = syllable(O, OO).toString()
    table["wi"] = syllable(O, WI).toString(); table["we"] = syllable(O, WE).toString()
    table["si"] = "시"; table["ti"] = "티"; table["tu"] = "투"; table["di"] = "디"; table["du"] = "두"
    table["sha"] = "샤"; table["shu"] = "슈"; table["sho"] = "쇼"; table["she"] = "셰"
    table["ja"] = "자"; table["ju"] = "주"; table["jo"] = "조"; table["je"] = "제"
    table["cha"] = "차"; table["chu"] = "추"; table["cho"] = "초"; table["che"] = "체"
    yRow("ky", K); yRow("gy", G); yRow("ny", N); yRow("hy", H); yRow("by", B); yRow("py", P)
    yRow("my", M); yRow("ry", R)
    table.entries.map { it.key to it.value }.sortedByDescending { it.first.length }
}

/** Adds the final [final] to the last syllable of [out], when it has none yet. */
private fun addFinal(out: StringBuilder, final: Int): Boolean {
    if (out.isEmpty()) return false
    val last = out[out.length - 1].code - SYLLABLE_BASE
    if (last !in 0 until 11172 || last % 28 != 0) return false
    out.setCharAt(out.length - 1, (SYLLABLE_BASE + last + final).toChar())
    return true
}

/** One word of romaji in Hangul, or null when it isn't romaji. */
private fun word(romaji: String): String? {
    val text = romaji.lowercase()
    val out = StringBuilder()
    var at = 0
    while (at < text.length) {
        val c = text[at]
        // っ: a doubled consonant, or "tch".
        if (at + 1 < text.length && c !in "aiueon" && (text[at + 1] == c || (c == 't' && text[at + 1] == 'c'))) {
            if (!addFinal(out, FINAL_S)) return null
            at++
            continue
        }
        // ん: n before a consonant, an apostrophe, or the end.
        if (c == 'n' && (at + 1 == text.length || text[at + 1] !in "aiueoy")) {
            if (!addFinal(out, FINAL_N)) out.append('응')
            at += if (at + 1 < text.length && text[at + 1] == '\'') 2 else 1
            continue
        }
        val match = TABLE.firstOrNull { text.startsWith(it.first, at) } ?: return null
        out.append(match.second)
        at += match.first.length
    }
    return out.toString()
}

private val WORD = Regex("[A-Za-z']+")

/**
 * Google's romaji [reading] of the lyric line [source] in Hangul. Words written in Latin letters
 * in the line itself, such as "I love you", stay as they are, even where they look like romaji.
 */
internal fun romajiToHangul(reading: String, source: String = ""): String {
    val latin = WORD.findAll(source).mapTo(HashSet()) { it.value.lowercase() }
    // Long vowels lose their marks: ō is o, as the reading is for singing along.
    val plain = Normalizer.normalize(reading, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return WORD.replace(plain) { match ->
        if (match.value.lowercase() in latin) match.value else word(match.value) ?: match.value
    }
}
