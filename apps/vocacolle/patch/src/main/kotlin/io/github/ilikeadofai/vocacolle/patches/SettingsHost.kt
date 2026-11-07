package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.before
import app.reseam.patch.classTarget
import app.reseam.patch.method
import app.reseam.patch.settings.Brand
import app.reseam.patch.settings.Color
import app.reseam.patch.settings.ColorScheme
import app.reseam.patch.settings.openSettings
import app.reseam.patch.settings.settingsHost
import io.github.ilikeadofai.vocacolle.core.bindings.SettingsMenu

private const val SETTING_FRAGMENT = "Ljp/nicovideo/nicobox/ui/setting/SettingFragment"

/** `android.R.id.home`, the toolbar's up button, which the provider handles. */
private const val ANDROID_R_ID_HOME = 16908332L

/** `onMenuItemSelected` of the MenuProvider VocaColle's settings screen registers for its toolbar. */
private val settingsMenuSelected = method("settingsMenuSelected") {
    literals(ANDROID_R_ID_HOME)
    params("android.view.MenuItem")
    returns(Type.Boolean)
    custom { owner.startsWith("$SETTING_FRAGMENT$") }
}

private val settingsMenuProvider = classTarget("settingsMenuProvider") {
    bytecode.findClass(settingsMenuSelected.owner) ?: error("settings menu provider missing")
}

/** `onCreateMenu` of the same provider. */
private val settingsMenuCreate = method("settingsMenuCreate") {
    inClass(settingsMenuProvider)
    params("android.view.Menu", "android.view.MenuInflater")
    returns(Type.Void)
}

private fun hex(value: String) = Color.hex(value)

/** VocaColle's cyan, as Material 3 tonal roles. */
val vocacolleBrand = Brand(
    name = "VocaColle Reseam",
    light = ColorScheme(
        primary = hex("#006590"), onPrimary = hex("#FFFFFF"),
        primaryContainer = hex("#C8E6FF"), onPrimaryContainer = hex("#001E2F"),
        secondaryContainer = hex("#D2E5F5"), onSecondaryContainer = hex("#0B1D29"),
        surface = hex("#F7F9FF"), surfaceContainerLow = hex("#F1F4FA"),
        surfaceContainer = hex("#EBEEF4"), surfaceContainerHigh = hex("#E5E8EE"),
        surfaceContainerHighest = hex("#E0E3E8"), onSurface = hex("#181C20"),
        onSurfaceVariant = hex("#41474D"), outline = hex("#71787E"), outlineVariant = hex("#C1C7CE"),
        error = hex("#BA1A1A"), onError = hex("#FFFFFF"),
        errorContainer = hex("#FFDAD6"), onErrorContainer = hex("#410002"), scrim = hex("#000000"),
    ),
    dark = ColorScheme(
        primary = hex("#8BCEFF"), onPrimary = hex("#00344D"),
        primaryContainer = hex("#004C6E"), onPrimaryContainer = hex("#C8E6FF"),
        secondaryContainer = hex("#374955"), onSecondaryContainer = hex("#D2E5F5"),
        surface = hex("#101418"), surfaceContainerLow = hex("#181C20"),
        surfaceContainer = hex("#1C2024"), surfaceContainerHigh = hex("#262A2F"),
        surfaceContainerHighest = hex("#31353A"), onSurface = hex("#E0E3E8"),
        onSurfaceVariant = hex("#C1C7CE"), outline = hex("#8B9198"), outlineVariant = hex("#41474D"),
        error = hex("#FFB4AB"), onError = hex("#690005"),
        errorContainer = hex("#93000A"), onErrorContainer = hex("#FFDAD6"), scrim = hex("#000000"),
    ),
)

/**
 * The bundle's settings screen, opened from a "Reseam patches" item in the overflow menu of
 * VocaColle's own Settings toolbar.
 */
val vocacolleSettings = settingsHost("vocacolle") {
    compatibleWith(VOCACOLLE)
    brand(vocacolleBrand)

    install {
        settingsMenuCreate.before { SettingsMenu.addItem(param(0)) }
        settingsMenuSelected.before {
            whenTrue(SettingsMenu.isItem(param(0))) {
                openSettings()
                returnTrue()
            }
        }
    }
}
