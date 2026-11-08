@file:JvmName("TitleTranslator")

package io.github.ilikeadofai.vocacolle.core.translate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.core.language as uiLanguage
import io.github.ilikeadofai.vocacolle.core.hasVocaDbTitle
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Song-title translation, through Google Translate's public web endpoint (no key), or an
 * OpenAI-compatible chat completions endpoint with the user's own key. Like the VocaDB titles, a known translation shows at once and an unknown one is
 * fetched in the background for the player's next refresh. Only titles with Japanese in them are
 * sent, so a title VocaDB already replaced is left alone.
 */

private const val TAG = "VocaColleAi"
private const val BUNDLE = "vocacolle-reseam-patches"
private const val KEY_ALIAS = "vocacolle_reseam_ai_key"
private const val SECRETS = "vocacolle_reseam_secrets"
private const val SECRET_API_KEY = "ai_api_key"
private const val PROMPT_VERSION = 1
private const val MAX_TITLE_CHARS = 200
private const val MAX_BODY_BYTES = 128 * 1024
private val JAPANESE = Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF]")

@Volatile private var appContext: Context? = null

private val memory: MutableMap<String, String> = Collections.synchronizedMap(
    object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4096
    },
)
private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

/** [redraw]: a tag, whose chips read [tagRevision] and redraw when it changes. */
private class TitleJob(val config: Config, val key: String, val source: String, val redraw: Boolean = false)

// Titles to translate, newest first: what the user just scrolled to matters more than the rest of
// a list they have left. When it is full the oldest are dropped, to be asked for again on sight.
private val titleQueue = LinkedBlockingDeque<TitleJob>(300)

private val titleWorker: Thread by lazy {
    Thread({
        while (true) {
            val batch = mutableListOf(titleQueue.takeFirst())
            while (batch.size < TITLE_BATCH) batch += titleQueue.pollFirst() ?: break
            translateTitles(batch)
        }
    }, "$TAG-titles").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
}

private const val TITLE_BATCH = 16
private const val MAX_BATCH_QUERY_CHARS = 6_000

@Volatile private var currentActivity: java.lang.ref.WeakReference<Activity>? = null

@Export
fun init(context: Context?) {
    val application = context?.applicationContext as? Application ?: return
    if (appContext != null) return
    appContext = application
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = java.lang.ref.WeakReference(activity)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })
}

private fun translateTitles(batch: List<TitleJob>) {
    try {
        val missing = batch.filter { job ->
            val cached = cacheFile(job.key)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            if (cached != null) memory[job.key] = cached
            cached == null
        }
        val google = missing.filter { it.config.provider == PROVIDER_GOOGLE }
        val translated = if (google.size > 1) requestGoogleBatch(google) else emptyMap()
        for (job in missing) {
            if (job.key in translated) store(job.key, translated.getValue(job.key)) else translate(job.config, job.key, job.source)
        }
        if (batch.any { it.redraw && memory.containsKey(it.key) }) bumpTagRevision()
    } catch (failure: Exception) {
        Log.d(TAG, "title batch failed: ${failure.javaClass.simpleName}: ${failure.message}")
    } finally {
        batch.forEach { inFlight.remove(it.key) }
    }
}

/** The title to show; the given [title] until a translation is known. */
@Export
fun displayTitle(videoId: String?, title: String?): String? {
    if (title == null || !AiSettings.aiTitles.value) return title
    if (videoId != null && hasVocaDbTitle(videoId)) return title
    return shortText(title, redraw = false)
}

/** [text] translated if known, else [text], queued for the title worker. */
private fun shortText(text: String, redraw: Boolean): String {
    val source = text.trim()
    if (source.isEmpty() || source.length > MAX_TITLE_CHARS || !JAPANESE.containsMatchIn(source)) return text
    val config = config() ?: return text
    val key = cacheKey(config, source)
    memory[key]?.let { return it }
    if (inFlight.add(key)) {
        titleWorker
        val job = TitleJob(config, key, source, redraw)
        while (!titleQueue.offerFirst(job)) titleQueue.pollLast()?.let { inFlight.remove(it.key) }
    }
    return text
}

// Tags ---------------------------------------------------------------------------------------

/** A Compose state the tag chips read while drawing, so they redraw when translations arrive. */
private val tagRevision: Any? by lazy { newState(0) }

private fun bumpTagRevision() {
    mainHandler.post {
        val state = tagRevision ?: return@post
        writeState(state, ((readState(state) as? Int) ?: 0) + 1)
    }
}

/**
 * The text of a tag chip: [tag] translated, for display only. The chip's click keeps the
 * original tag, so searching and the server see Japanese. Called while composing the chip.
 */
@Export
fun displayTag(tag: String?): String? {
    if (tag == null || !AiSettings.aiTags.value) return tag
    tagRevision?.let { readState(it) }
    return shortText(tag, redraw = true)
}

private const val PROVIDER_GOOGLE = "google"
private const val GOOGLE_DICT_ENDPOINT = "https://clients5.google.com/translate_a/t"
private const val GOOGLE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"

private class Config(val provider: String, val baseUrl: String, val model: String, val language: String, val apiKey: String)

/** The target language: the one chosen, or with "auto", the Translate UI patch's, else the device's. */
private fun targetLanguage(): String {
    val chosen = AiSettings.aiLanguage.value
    if (chosen == "ko" || chosen == "en") return chosen
    return try {
        uiLanguage()
    } catch (_: Throwable) {
        // The Translate UI patch didn't run, so nothing implemented its language.
        if (Locale.getDefault().language == "ko") "ko" else "en"
    }
}

private fun config(): Config? {
    val language = targetLanguage()
    if (AiSettings.aiProvider.value == PROVIDER_GOOGLE) return Config(PROVIDER_GOOGLE, GOOGLE_ENDPOINT, "", language, "")
    val baseUrl = AiSettings.aiBaseUrl.value.trim().trimEnd('/')
    val model = AiSettings.aiModel.value.trim()
    if (!baseUrl.startsWith("https://") || model.isEmpty()) return null
    val apiKey = readApiKey() ?: return null
    return Config("openai", baseUrl, model, language, apiKey)
}

/** The cache key covers everything that changes the answer, and never the API key. */
private fun cacheKey(config: Config, source: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("v$PROMPT_VERSION|${config.provider}|${config.baseUrl}|${config.model}|${config.language}|$source".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(40)
}

private fun cacheFile(key: String) = appContext?.cacheDir?.let { File(File(it, "vocacolle-reseam/ai"), key) }

private fun translate(config: Config, key: String, source: String, kind: Kind = Kind.TITLE): String? {
    try {
        memory[key]?.let { return it }
        cacheFile(key)?.takeIf { it.isFile }?.let { file ->
            return file.readText(Charsets.UTF_8).also { memory[key] = it }
        }
        val translated = request(config, source, kind) ?: return null
        store(key, translated)
        return translated
    } catch (failure: Exception) {
        // Never log the key or the response; the status line is enough to debug a setup.
        Log.d(TAG, "translation failed: ${failure.javaClass.simpleName}: ${failure.message}")
        return null
    } finally {
        inFlight.remove(key)
    }
}

private fun store(key: String, translated: String) {
    memory[key] = translated
    try {
        cacheFile(key)?.let { file ->
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$key.tmp")
            temp.writeText(translated, Charsets.UTF_8)
            if (!temp.renameTo(file)) temp.delete()
        }
    } catch (_: IOException) {
        // Still shown from memory for this session.
    }
}

/**
 * Several titles in one Google request: the Chrome dictionary client takes one `q` per text and
 * answers with the translations in order. Anything that doesn't line up is left for one-by-one.
 */
private fun requestGoogleBatch(jobs: List<TitleJob>, kind: Kind = Kind.TITLE): Map<String, String> {
    val language = jobs.first().config.language
    val chunk = ArrayList<TitleJob>()
    val result = HashMap<String, String>()
    fun flush() {
        if (chunk.isEmpty()) return
        val query = chunk.joinToString("&") { "q=" + URLEncoder.encode(it.source, "UTF-8") }
        val reply = try {
            JSONArray(get("$GOOGLE_DICT_ENDPOINT?client=dict-chrome-ex&sl=ja&tl=$language&$query"))
        } catch (_: Exception) {
            null
        }
        if (reply != null && reply.length() == chunk.size) {
            chunk.forEachIndexed { index, job ->
                val item = reply.get(index)
                val text = if (item is JSONArray) item.optString(0) else item.toString()
                clean(text, job.source, kind)?.let { result[job.key] = it }
            }
        }
        chunk.clear()
    }
    var length = 0
    for (job in jobs.filter { it.config.language == language }) {
        val cost = URLEncoder.encode(job.source, "UTF-8").length + 3
        if (length + cost > MAX_BATCH_QUERY_CHARS) { flush(); length = 0 }
        chunk += job
        length += cost
    }
    flush()
    return result
}

/** What is being translated: decides the prompt and what counts as a sane answer. */
private enum class Kind { TITLE, TEXT }

private fun request(config: Config, source: String, kind: Kind): String? =
    if (config.provider == PROVIDER_GOOGLE) requestGoogle(config, source, kind) else requestOpenAi(config, source, kind)

// Descriptions -----------------------------------------------------------------------------

private const val MAX_DESCRIPTION_CHARS = 6_000
private const val MAX_SEGMENTS = 200
private const val DESCRIPTION_WAIT_MS = 5_000L
private val TAG_OR_TEXT = Regex("<[^>]*>|[^<]+")

// Descriptions are wanted while their screen opens, so they get their own small pool, apart
// from the title queue that may be busy with a playlist.
private val descriptionWorkers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_SEGMENTS), { runnable ->
    Thread(runnable, "$TAG-text").apply { isDaemon = true }
}, ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }

/**
 * A work's description, translated piece by piece between its HTML tags, so links, line breaks,
 * and formatting stay as they are. Off the main thread it waits a few seconds for the pieces;
 * on it, it uses what is already known and fetches the rest for the next time.
 */
@Volatile private var lastDescription: String? = null

/** Remembers the description of the work whose detail screen is opening, for the Translate button. */
@Export
fun rememberDescription(description: String?) {
    lastDescription = description
}

/** The app's `mutableStateOf(value)`: a Compose state, so the screen redraws when it changes. */
@Export
external fun newState(value: Any?): Any?

@Export
external fun readState(state: Any?): Any?

@Export
external fun writeState(state: Any?, value: Any?)

/** The description shown in place of the original, as `[original, translation]`, or null. */
private val shownTranslation: Any? by lazy { newState(null) }

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/** The text the description area draws: [original], or its translation once the button asked. */
@Export
fun shownDescription(original: String?): String? {
    val shown = readState(shownTranslation) as? Array<*> ?: return original
    return if (shown.getOrNull(0) == original) shown.getOrNull(1) as? String ?: original else original
}

/**
 * Runs in the update block of the description's AndroidView, which draws the text in a
 * TextView made once. Reading the state here makes the block run again when the translation
 * arrives or is taken back, and the TextView gets that text, links and all.
 */
@Export
fun updateDescriptionView(view: Any?) {
    val textView = view as? android.widget.TextView ?: return
    val shown = readState(shownTranslation) as? Array<*>
    val original = lastDescription
    val wanted = if (shown != null && shown.getOrNull(0) == original) shown.getOrNull(1) as? String else null
    val current = textView.getTag(SHOWN_TAG) as? String
    if (wanted == current) return
    val html = wanted ?: original ?: return
    textView.text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
    textView.setTag(SHOWN_TAG, wanted)
}

// A key no app view uses: ids are 0x7fXXXXXX in the app and 0x01XXXXXX in the framework.
private const val SHOWN_TAG = 0x0352_5E5A

/** The button's label: Translate, or Show original while the translation is shown. */
@Export
fun translateLabel(): String {
    val shown = readState(shownTranslation) as? Array<*>
    return if (shown != null && shown.getOrNull(0) == lastDescription) VocaColleStrings.showOriginal else VocaColleStrings.translate
}

/**
 * The button under a work's description: translates it in the background and puts the
 * translation in place of the original, or puts the original back.
 */
@Export
fun showDescriptionTranslation() {
    val description = lastDescription ?: return
    val shown = readState(shownTranslation) as? Array<*>
    if (shown != null && shown.getOrNull(0) == description) {
        writeState(shownTranslation, null)
        return
    }
    val activity = currentActivity?.get()
    if (config() == null) {
        activity?.let { Toast.makeText(it, VocaColleStrings.translationNotSetUp, Toast.LENGTH_SHORT).show() }
        return
    }
    activity?.let { Toast.makeText(it, VocaColleStrings.translating, Toast.LENGTH_SHORT).show() }
    Thread({
        val translated = try {
            translatePieces(description, wait = true)
        } catch (_: Exception) {
            description
        }
        mainHandler.post {
            if (translated == description) {
                activity?.let { Toast.makeText(it, VocaColleStrings.translationFailed, Toast.LENGTH_LONG).show() }
            } else if (lastDescription == description) {
                writeState(shownTranslation, arrayOf(description, translated))
            }
        }
    }, "$TAG-description").apply { isDaemon = true }.start()
}

/** A notice's title, the same way: waited for off the main thread. */
@Export
fun displayNoticeTitle(title: String?): String? {
    if (title == null || !AiSettings.aiNotices.value) return title
    return translatePieces(title, wait = Looper.myLooper() != Looper.getMainLooper())
}

/**
 * A notice's article, opened through Google Translate's page proxy, which translates the page
 * and keeps its links working. Only web pages are wrapped; anything else opens as it is.
 */
@Export
fun noticeArticleUrl(url: String?): String? {
    if (url == null || !AiSettings.aiNotices.value) return url
    if (!url.startsWith("https://") && !url.startsWith("http://")) return url
    return "https://translate.google.com/translate?sl=ja&tl=${AiSettings.aiLanguage.value}&hl=${AiSettings.aiLanguage.value}&u=" +
        URLEncoder.encode(url, "UTF-8")
}

/**
 * [description] with the Japanese text between its HTML tags translated line by line: the
 * dictionary client joins lines it is given together, so each line goes on its own, batched.
 * With [wait] the lines are translated on this thread; without, in the background for next time.
 */
private fun translatePieces(description: String, wait: Boolean): String {
    if (description.length > MAX_DESCRIPTION_CHARS || !JAPANESE.containsMatchIn(description)) return description
    val config = config() ?: return description
    val units = TAG_OR_TEXT.findAll(description).map { it.value }.toList()
        .flatMap { part -> if (part.startsWith("<")) listOf(part) else part.split(LINE_END) }
    val lines = units.filter { !it.startsWith("<") && JAPANESE.containsMatchIn(it) }.map { it.trim() }.distinct()
    if (lines.isEmpty() || lines.size > MAX_SEGMENTS) return description
    val keys = lines.associateWith { cacheKey(config, "line|$it") }
    val missing = lines.filter { line ->
        val key = keys.getValue(line)
        memory[key] == null && cacheFile(key)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.also { memory[key] = it } == null
    }
    if (missing.isNotEmpty()) {
        if (wait) {
            translateLines(config, missing, keys)
        } else {
            try {
                descriptionWorkers.execute { translateLines(config, missing, keys) }
            } catch (_: RuntimeException) {
                // Busy: shown in Japanese this time.
            }
        }
    }
    return units.joinToString("") { unit ->
        if (unit.startsWith("<")) unit else keys[unit.trim()]?.let { memory[it] }?.let { keepEdges(unit, it) } ?: unit
    }
}

private val LINE_END = Regex("(?<=\\n)")

private fun translateLines(config: Config, lines: List<String>, keys: Map<String, String>) {
    val jobs = lines.map { TitleJob(config, keys.getValue(it), it) }
    val batched = if (config.provider == PROVIDER_GOOGLE) {
        try {
            requestGoogleBatch(jobs, Kind.TEXT)
        } catch (failure: Exception) {
            Log.d(TAG, "line batch failed: ${failure.javaClass.simpleName}")
            emptyMap()
        }
    } else {
        emptyMap()
    }
    for (job in jobs) {
        val translated = batched[job.key]
        if (translated != null) store(job.key, translated) else translate(config, job.key, job.source, Kind.TEXT)
    }
    Log.i(TAG, "translated ${jobs.count { memory[it.key] != null }} of ${jobs.size} description lines")
}

/** The translation with the original's leading and trailing whitespace, which services drop. */
private fun keepEdges(original: String, translated: String): String {
    val lead = original.takeWhile { it.isWhitespace() }
    val trail = original.takeLastWhile { it.isWhitespace() }
    return lead + translated.trim() + trail
}

/**
 * Google Translate's public web clients, as browser extensions use them: no key. The Chrome
 * dictionary client answers `["translated"]`; the older `gtx` one, tried when that fails,
 * answers `[[["translated", "source", …], …], …]`.
 */
private fun requestGoogle(config: Config, source: String, kind: Kind): String? {
    val q = URLEncoder.encode(source, "UTF-8")
    return try {
        val reply = JSONArray(get("$GOOGLE_DICT_ENDPOINT?client=dict-chrome-ex&sl=ja&tl=${config.language}&q=$q"))
        val first = reply.get(0)
        clean(if (first is JSONArray) first.optString(0) else first.toString(), source, kind)
    } catch (failure: Exception) {
        val sentences = JSONArray(get("$GOOGLE_ENDPOINT?client=gtx&sl=ja&tl=${config.language}&dt=t&q=$q")).getJSONArray(0)
        clean((0 until sentences.length()).joinToString("") { sentences.getJSONArray(it).optString(0) }, source, kind)
    }
}

private fun get(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BODY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8)
        }
    } finally {
        connection.disconnect()
    }
}

private fun clean(content: String, source: String, kind: Kind): String? {
    if (kind == Kind.TEXT) {
        // Lines must survive, or the description's layout falls apart.
        val text = content.trim()
        val lines = { value: String -> value.trim().count { it == '\n' } }
        return text.takeIf { it.isNotEmpty() && it.length <= source.length * 4 + 40 && lines(text) == lines(source) }
    }
    // A title reply that isn't a single short line gets no say in the UI.
    val line = content.trim().trim('"', '“', '”', '「', '」').trim()
    return line.takeIf { it.isNotEmpty() && '\n' !in it && it.length <= MAX_TITLE_CHARS * 2 && it != source }
}

private fun requestOpenAi(config: Config, source: String, kind: Kind): String? {
    val target = if (config.language == "en") "English" else "Korean"
    val body = JSONObject()
        .put("model", config.model)
        .put("temperature", 0)
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject().put("role", "system").put(
                        "content",
                        if (kind == Kind.TITLE) {
                            "You translate Japanese song titles into $target for a music player. " +
                                "Reply with the translated title only, on one line, without quotes or notes. " +
                                "Keep names of people, groups, and works in their usual $target form, " +
                                "keep Latin-script parts and symbols as they are, and keep the title short."
                        } else {
                            "You translate part of a Japanese video description into $target. " +
                                "Reply with the translation only. Keep exactly the same line breaks, " +
                                "URLs, hashtags, video ids such as sm12345, and symbols."
                        },
                    ),
                )
                .put(JSONObject().put("role", "user").put("content", source)),
        )
    val connection = URL("${config.baseUrl}/chat/completions").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        val text = connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BODY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8)
        }
        val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
        return clean(content, source, kind)
    } finally {
        connection.disconnect()
    }
}

// API key ----------------------------------------------------------------------------------

private fun keystoreKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build(),
    )
    return generator.generateKey()
}

private fun secrets(context: Context) = context.getSharedPreferences(SECRETS, Context.MODE_PRIVATE)

private fun readApiKey(): String? {
    val context = appContext ?: return null
    val stored = secrets(context).getString(SECRET_API_KEY, null) ?: return null
    return try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (failure: Exception) {
        Log.w(TAG, "stored API key is unreadable; set it again")
        null
    }
}

private fun writeApiKey(context: Context, apiKey: String?) {
    val editor = secrets(context).edit()
    if (apiKey.isNullOrBlank()) {
        editor.remove(SECRET_API_KEY)
    } else {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        editor.putString(SECRET_API_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
    }
    editor.apply()
    synchronized(memory) { memory.clear() }
}

/** The settings screen's "API key" row: a dialog to paste, replace, or remove the key. */
@Export
fun editApiKey(activity: Activity?) {
    if (activity == null) return
    appContext = appContext ?: activity.applicationContext
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val field = ui.textField(VocaColleStrings.aiApiKeyHint)
    field.field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    ui.dialog()
        .title(VocaColleStrings.aiApiKey)
        .message(VocaColleStrings.aiApiKeySummary)
        .view(field)
        .dismissAction(VocaColleStrings.cancel)
        .action(VocaColleStrings.remove) {
            writeApiKey(activity, null)
            Toast.makeText(activity, VocaColleStrings.aiApiKeyCleared, Toast.LENGTH_SHORT).show()
        }
        .action(VocaColleStrings.save) {
            val value = field.text.trim()
            if (value.isNotEmpty()) {
                writeApiKey(activity, value)
                Toast.makeText(activity, VocaColleStrings.aiApiKeySaved, Toast.LENGTH_SHORT).show()
            }
        }
        .show()
}
