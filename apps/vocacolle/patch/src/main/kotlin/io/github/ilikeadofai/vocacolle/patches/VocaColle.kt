package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.CompatiblePackage
import app.reseam.patch.FieldTarget
import app.reseam.patch.MethodTarget
import app.reseam.patch.dex.ref
import app.reseam.patch.fieldTarget
import app.reseam.patch.invoke

const val VOCACOLLE_PACKAGE = "jp.nicovideo.nicobox"

/**
 * VocaColle 7.48.0 (versionCode 187). Targets are found by structure, strings, and resource ids
 * rather than obfuscated names, but the translation catalog and hooks were checked against this
 * build only, so other versions are skipped unless the user asks for them.
 */
val VOCACOLLE: CompatiblePackage = VOCACOLLE_PACKAGE("7.48.0", 187)

/**
 * `kotlin.Unit.INSTANCE` as the app's R8 build names it, found from a method that returns Unit.
 * R8 renames both the class and the field, so the only stable handle is a method's return type.
 */
fun unitInstance(returningUnit: MethodTarget): FieldTarget = fieldTarget("kotlin.Unit") {
    val unit = bytecode.findClass(returningUnit.returnType)
        ?: error("${returningUnit.descriptor} doesn't return an app class")
    unit.staticFields.singleOrNull { it.fieldType == unit.descriptor }?.ref
        ?: error("${unit.descriptor} doesn't look like kotlin.Unit")
}
