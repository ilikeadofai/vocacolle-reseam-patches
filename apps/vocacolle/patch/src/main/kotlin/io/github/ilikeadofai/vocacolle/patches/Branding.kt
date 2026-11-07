package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.OptionPath
import app.reseam.patch.PatchRuntime
import app.reseam.patch.patch

private const val NAME_RESOURCE = "vocacolle_reseam_app_name"
private const val ICON_RESOURCE = "vocacolle_reseam_icon"
private const val MAX_ICON_BYTES = 20 * 1024 * 1024
private const val MAX_ICON_SIDE = 4096
private val DENSITIES = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Checks that [bytes] is a square PNG of a sensible size, and returns its side in pixels. */
private fun squarePngSide(bytes: ByteArray, file: String): Int {
    require(bytes.size in 33..MAX_ICON_BYTES) { "$file must be a PNG of at most 20 MiB" }
    require(bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "$file is not a PNG" }
    fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
    val width = int(16)
    val height = int(20)
    require(width == height && width in 1..MAX_ICON_SIDE) {
        "$file must be square and at most ${MAX_ICON_SIDE}px, not ${width}x$height"
    }
    return width
}

/**
 * The icon files in [folder]: `mipmap-<density>/ic_launcher.png` for any of mdpi to xxxhdpi,
 * or a single `icon.png`, which launchers scale from one size.
 */
private fun iconFiles(folder: OptionPath): Map<String, ByteArray> {
    // readFile fails on a missing file, so look before reading.
    val contents = folder.listContents().map { it.replace('\\', '/').trimStart('/') }.toSet()
    fun has(path: String) = path in contents || path.substringBefore('/') in contents
    fun read(path: String) = folder.readFile(path) ?: error("couldn't read $path in $folder")
    val perDensity = DENSITIES.filter { has("mipmap-$it") }.mapNotNull { density ->
        runCatching { read("mipmap-$density/ic_launcher.png") }.getOrNull()?.let { density to it }
    }.toMap()
    if (perDensity.isNotEmpty()) return perDensity
    require(has("icon.png")) { "$folder has neither icon.png nor mipmap-<density>/ic_launcher.png" }
    return mapOf("nodpi" to read("icon.png"))
}

private fun PatchRuntime.applyIcon(folder: OptionPath) {
    var id: UInt? = null
    for ((density, bytes) in iconFiles(folder)) {
        val file = if (density == "nodpi") "icon.png" else "mipmap-$density/ic_launcher.png"
        squarePngSide(bytes, file)
        id = resources.addFile("mipmap", ICON_RESOURCE, "res/$ICON_RESOURCE-$density.png", bytes, density)
    }
    // A new resource with no adaptive (anydpi-v26) variant, so every Android version shows the
    // PNG as given instead of VocaColle's adaptive foreground and background layers.
    manifest.edit {
        val application = findByTag("application").single()
        application.setResourceRef("android:icon", id!!)
        application.setResourceRef("android:roundIcon", id)
    }
}

@Suppress("unused")
val customBranding = patch("Custom branding") {
    description(
        """
        Changes the launcher name and icon. With no options set it changes nothing, so the
        original ボカコレ name and icon stay. The name is a separate resource, so text inside the
        app that says ボカコレ is not affected.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    val appName = stringOption(
        "appName",
        title = "App name",
        description = "The name under the launcher icon. Leave empty to keep ボカコレ.",
        default = "",
    )
    val icon = pathOption(
        "icon",
        title = "Icon folder",
        description = "A folder with a square icon.png, or with mipmap-mdpi … mipmap-xxxhdpi folders " +
            "each holding a square ic_launcher.png (48, 72, 96, 144, and 192 px).",
    )

    execute {
        val name = options[appName].trim()
        val folder = options.getOrNull(icon)
        if (name.isNotEmpty()) {
            val id = resources.addString(NAME_RESOURCE, name) ?: error("couldn't add the app name")
            manifest.edit { findByTag("application").single().setResourceRef("android:label", id) }
        }
        if (folder != null) applyIcon(folder)
        if (name.isEmpty() && folder == null) log.info("No name or icon given; branding left as it is")
    }
}
