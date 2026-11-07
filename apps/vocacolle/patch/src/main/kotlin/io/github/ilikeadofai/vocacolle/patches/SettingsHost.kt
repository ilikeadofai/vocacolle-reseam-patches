package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.PatchRuntime
import app.reseam.patch.PointTarget
import app.reseam.patch.Type
import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.methods
import app.reseam.patch.point
import app.reseam.patch.types.EncodedVal
import app.reseam.patch.before
import app.reseam.patch.classTarget
import app.reseam.patch.method
import app.reseam.patch.settings.Brand
import app.reseam.patch.settings.Color
import app.reseam.patch.settings.ColorScheme
import app.reseam.patch.settings.openSettings
import app.reseam.patch.settings.settingsHost
import io.github.ilikeadofai.vocacolle.core.bindings.OpenReseamSettings
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

// Compose defaults mask of VocaColle's settings row: every argument but the title and the click.
private const val ROW_DEFAULTS = 253

/**
 * The call that draws the "About VocaColle" row in the settings list. The app reads the title's
 * id from an R class field, so the list is found by that field, and the row is the next call to
 * the 12-argument row composable after it.
 */
private fun PatchRuntime.appInfoRow(): PointTarget {
    val appInfo = resources.id("string", "app_info") ?: error("the app has no string/app_info")
    val fields = bytecode.classes.asSequence()
        .flatMap { it.staticFields.asSequence() }
        .filter { it.fieldType == Type.Int && (it.initialValue as? EncodedVal.IntVal)?.field0 == appInfo.toInt() }
        .toList()
    val rows = fields.flatMap { field ->
        methods("settingsList") {
            accesses { owner(field.classDescriptor); name(field.name) }
            // The About screen uses the same title in its own list.
            custom { classDef.sourceFile == "SettingScreen.kt" }
        }.all.mapNotNull { list ->
            runCatching {
                list.point("appInfoTitle") { field { owner(field.classDescriptor); name(field.name) } }
                    .next { invokeStatic { returns(Type.Void); paramCount(12) } }
                    .also { it.index }
            }.getOrNull()
        }
    }
    return rows.singleOrNull() ?: error("expected one About VocaColle settings row, found ${rows.size}")
}

/** A "Reseam patches" row right after "About VocaColle", drawn by the app's own row composable. */
private fun PatchRuntime.addSettingsRow() {
    val appInfoRow = appInfoRow()
    val settingsRow = appInfoRow.callee("settingsRow")
    val clickType = bytecode.findClass(settingsRow.parameterTypes[8]) ?: error("the settings row has no click type")
    val invoke = clickType.virtualMethods.singleOrNull()
    check(clickType.isInterface && invoke != null && invoke.returnType == Type.Object) {
        "the settings row no longer takes a Function0 click"
    }
    OpenReseamSettings.target.classDef.addInterface(clickType.descriptor)
    OpenReseamSettings.target.addMethod(invoke.name, invoke.proto) {
        openSettings()
        // Compose ignores what an onClick returns.
        returnNull()
    }
    appInfoRow.captureArgumentAs("composer", 9).after {
        call(
            settingsRow,
            nullObject, SettingsMenu.rowTitle(), nullObject, nullObject, nullObject, long(0), nullObject, long(0),
            newInstance(OpenReseamSettings.descriptor), capture("composer"), int(0), int(ROW_DEFAULTS),
        )
    }
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
 * The bundle's settings screen, opened from a "Reseam patches" row in VocaColle's own settings
 * list, and from the settings toolbar's menu where the app shows one.
 */
val vocacolleSettings = settingsHost("vocacolle") {
    compatibleWith(VOCACOLLE)
    brand(vocacolleBrand)

    install {
        addSettingsRow()
        settingsMenuCreate.before { SettingsMenu.addItem(param(0)) }
        settingsMenuSelected.before {
            whenTrue(SettingsMenu.isItem(param(0))) {
                openSettings()
                returnTrue()
            }
        }
    }
}
