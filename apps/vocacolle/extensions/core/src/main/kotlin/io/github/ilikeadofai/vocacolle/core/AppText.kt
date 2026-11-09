@file:JvmName("AppText")

package io.github.ilikeadofai.vocacolle.core

import android.content.Context
import org.json.JSONObject

/*
 * Reseam's string tables follow the phone's language. Inside VocaColle, text should be in the
 * language VocaColle was patched into, so it reads the bundle's own table for that language.
 */

private const val TABLE = "reseam/vocacolle-reseam-patches/strings/VocaColleStrings"

@Volatile private var table: JSONObject? = null
@Volatile private var loaded = false

private fun appTable(): JSONObject? {
    if (loaded) return table
    synchronized(TABLE) {
        if (loaded) return table
        table = try {
            // The Translate UI patch implements language(); without it, there's nothing to follow.
            val file = when (language()) {
                "ko" -> "ko.json"
                "en" -> "default.json"
                else -> null
            }
            val context = appContext() ?: return null
            file?.let { name -> context.assets.open("$TABLE/$name").use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) } }
        } catch (_: Throwable) {
            null
        }
        loaded = true
        return table
    }
}

/** The app, from the patches that keep it, else from Android's own record of it. */
private fun appContext(): Context? = io.github.ilikeadofai.vocacolle.core.translate.translationContext()
    ?: io.github.ilikeadofai.vocacolle.core.library.libraryContext()
    ?: try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

/** The string [name] in VocaColle's patched language, else as Reseam picks it. */
internal inline fun appText(name: String, fallback: () -> String): String =
    appTable()?.optString(name)?.takeIf { it.isNotEmpty() } ?: fallback()
