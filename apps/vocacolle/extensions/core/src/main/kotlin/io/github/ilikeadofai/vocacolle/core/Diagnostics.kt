@file:JvmName("Diagnostics")

package io.github.ilikeadofai.vocacolle.core

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/*
 * What decides whether login, guest login, and high-quality audio work, gathered in one place.
 * Nothing here changes how the app logs in or plays: it reads the installed package, the
 * clock, and what the app itself recorded, so a failure can be told apart from a patch bug.
 */

private const val BUNDLE = "vocacolle-reseam-patches"

/** The certificates VocaColle 7.48.0 is signed with on Google Play (a rotated key, two signers). */
private val ORIGINAL_SIGNERS = setOf(
    "8c058ea91d602cc5aad771e5d92a17cdac39cbc904e8a00e654b66fa9682c611",
    "84f14b7088a02fc00d0ec3d96429f66510d5eb412b6ed058b36c93d8fe035674",
)

@Volatile private var lastLoginError: Pair<Long, String>? = null
@Volatile private var lastGuestError: Pair<Long, String>? = null
@Volatile private var lastAudio: String? = null

/** The web login's error code the app is about to show, such as `WL_E08`. */
@Export
fun recordLoginError(code: Any?) {
    if (code != null) lastLoginError = System.currentTimeMillis() to code.toString()
}

/** How the app classified a guest login failure. */
@Export
fun recordGuestError(type: Any?) {
    if (type != null) lastGuestError = System.currentTimeMillis() to type.toString()
}

/**
 * The audio the player chose: [highQuality] is the high-quality stream the server marked
 * playable for this account, or null; [using] is whether the player uses it; [exists] is
 * whether the work has one at all.
 */
@Export
fun recordAudio(highQuality: Any?, using: Boolean, exists: Boolean) {
    lastAudio = when {
        highQuality != null && using -> VocaColleStrings.diagAudioUsing
        highQuality != null -> VocaColleStrings.diagAudioOffered
        exists -> VocaColleStrings.diagAudioNotForAccount
        else -> VocaColleStrings.diagAudioNone
    }
}

private fun signers(context: Context): List<String> = try {
    val digests = if (Build.VERSION.SDK_INT >= 28) {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        (if (signing?.hasMultipleSigners() == true) signing.apkContentsSigners else signing?.signingCertificateHistory)
            ?.map { it.toByteArray() }.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() }.orEmpty()
    }
    digests.map { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
} catch (_: Exception) {
    emptyList()
}

private fun playServices(context: Context): String = try {
    context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "?"
} catch (_: PackageManager.NameNotFoundException) {
    VocaColleStrings.diagNotInstalled
}

/** The device clock minus the server's, in seconds, from the `Date` header; null when unreachable. */
private fun clockOffset(): Long? {
    for (url in listOf("https://nvapi.nicovideo.jp/", "https://www.google.com/generate_204")) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.responseCode
            val server = connection.getHeaderFieldDate("Date", 0)
            connection.disconnect()
            if (server > 0) return (System.currentTimeMillis() - server) / 1000
        } catch (_: Exception) {
            // Try the next one.
        }
    }
    return null
}

private fun time(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))

/** The diagnostics, as a dialog over the Reseam patches screen, with a copy button. */
@Export
fun openDiagnostics(activity: Activity?) {
    if (activity == null) return
    Toast.makeText(activity, VocaColleStrings.diagChecking, Toast.LENGTH_SHORT).show()
    Thread({
        val signers = signers(activity)
        val original = signers.any { it in ORIGINAL_SIGNERS }
        val offset = clockOffset()
        val lines = buildList {
            add(VocaColleStrings.diagSignature + ": " + if (original) VocaColleStrings.diagSignatureOriginal else VocaColleStrings.diagSignatureResigned)
            signers.forEach { add("  SHA-256 ${it.take(16)}…") }
            add(VocaColleStrings.diagPlayServices + ": " + playServices(activity))
            add(
                VocaColleStrings.diagClock + ": " + when {
                    offset == null -> VocaColleStrings.diagUnreachable
                    Math.abs(offset) <= 60 -> String.format(VocaColleStrings.diagClockOk, offset)
                    else -> String.format(VocaColleStrings.diagClockOff, offset)
                },
            )
            add(VocaColleStrings.diagLastLogin + ": " + (lastLoginError?.let { "${it.second} (${time(it.first)})" } ?: VocaColleStrings.diagNone))
            add(VocaColleStrings.diagLastGuest + ": " + (lastGuestError?.let { "${it.second} (${time(it.first)})" } ?: VocaColleStrings.diagNone))
            add(VocaColleStrings.diagAudio + ": " + (lastAudio ?: VocaColleStrings.diagNone))
            add("")
            add(if (original) VocaColleStrings.diagAdviceOriginal else VocaColleStrings.diagAdviceResigned)
            if (offset != null && Math.abs(offset) > 60) add(VocaColleStrings.diagAdviceClock)
        }
        val report = lines.joinToString("\n")
        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            Reseam.bundle(BUNDLE).ui(activity).dialog()
                .title(VocaColleStrings.diagnostics)
                .message(report)
                .dismissAction(VocaColleStrings.close)
                .action(VocaColleStrings.diagCopy) {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(VocaColleStrings.diagnostics, report))
                    Toast.makeText(activity, VocaColleStrings.favoritesCopied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }, "VocaColleDiagnostics").apply { isDaemon = true }.start()
}
