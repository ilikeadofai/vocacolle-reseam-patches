package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.after
import app.reseam.patch.before
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.methods
import app.reseam.patch.patch
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.bindings.Diagnostics
import io.github.ilikeadofai.vocacolle.settings.DiagnosticsSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `WebLoginErrorCode`: WL_E01 … WL_E20, the codes login errors show. */
private val webLoginErrorCode = klass("WebLoginErrorCode") { sourceFile("WebLoginErrorCode.kt") }

/** The login error message: `(context, messageResId, code)`, with "… (WL-E08)" at the end. */
private val webLoginErrorMessage = method("webLoginErrorMessage") {
    custom {
        classDef.sourceFile == "WebLoginErrorResolver.kt" && parameterTypes.size == 3 &&
            parameterTypes[0] == "Landroid/content/Context;" && parameterTypes[1] == Type.Int
    }
}

/** The resolver that classifies each guest login failure. */
private val guestLoginErrorTypes = methods("guestLoginErrorTypeResolver") {
    custom {
        classDef.sourceFile == "GuestLoginErrorTypeResolver.kt" && name != "<init>" &&
            parameterTypes.size == 1 && returnType.startsWith("L")
    }
}

/** `AudioInfoExtractor`'s choice: `(normal, highQualityIfPlayable, useHighQuality, hasHighQuality)`. */
private val audioChoice = method("audioInfoChoice") {
    custom {
        classDef.sourceFile == "AudioInfoExtractor.kt" && parameterTypes.size == 4 &&
            parameterTypes[2] == Type.Boolean && parameterTypes[3] == Type.Boolean
    }
}

@Suppress("unused")
val diagnostics = patch("Diagnostics") {
    description(
        """
        Adds a Diagnostics row to the Reseam patches screen: whether the app keeps VocaColle's
        original signature (login and guest login need it for App Check), the Play services
        version, the device clock against the server's, the last login and guest login error,
        and whether the server offered high-quality audio for the last track. Changes nothing.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(vocacolleSettings, section(VocaColleStrings.sectionDiagnostics, DiagnosticsSettings.diagnostics))

    execute {
        onAction(DiagnosticsSettings.diagnostics) { Diagnostics.openDiagnostics(activity) }
        check(webLoginErrorMessage.parameterTypes[2] == webLoginErrorCode.descriptor) {
            "the login error message no longer takes a WebLoginErrorCode"
        }
        webLoginErrorMessage.before { Diagnostics.recordLoginError(param(2)) }
        val guest = guestLoginErrorTypes.all
        check(guest.isNotEmpty()) { "no guest login error resolver found" }
        guest.forEach { it.after { Diagnostics.recordGuestError(result) } }
        audioChoice.before { Diagnostics.recordAudio(param(1), param(2), param(3)) }
    }
}
