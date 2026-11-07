package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.dex.ref
import app.reseam.patch.types.EncodedVal
import app.reseam.patch.enumConstant
import app.reseam.patch.point
import app.reseam.patch.points
import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.after
import app.reseam.patch.before
import app.reseam.patch.classTarget
import app.reseam.patch.fieldOfType
import app.reseam.patch.fieldTarget
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.methods
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.settings.AdSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

private const val PREMIUM_MERIT_LEAD_DIALOG = "Ljp/nicovideo/nicobox/ui/premiummerit/PremiumMeritLeadDialog;"
private const val HOME_FRAGMENT = "jp.nicovideo.nicobox.ui.home.HomeFragment"

// App-open ads -----------------------------------------------------------------------------

/** `AppOpenAdProvider.isAllowed(context)`: a suspend function answering whether to show one. */
private val appOpenAdAllowed = method("appOpenAdAllowed") {
    strings("App Open Ad is not allowed. (premium member)")
    hasParam("android.content.Context")
    returns(Type.Object)
}

// Banner and in-feed ads -------------------------------------------------------------------

/** `DisplayAdViewController.loadAd()`, the one place every display ad starts loading. */
private val displayAdLoad = method("displayAdLoad") {
    strings("loadAd(")
    params()
    returns(Type.Void)
    custom { classDef.sourceFile == "DisplayAdViewController.kt" }
}

private val displayAdExistence = klass("displayAdExistence") {
    enumConstants("UNRESOLVED", "NONE", "EXIST")
}
private val displayAdNone = displayAdExistence.enumConstant("NONE")

/** Where the controller reports "no ad here", which collapses the ad's slot in the layout. */
private val reportNoDisplayAd = method("displayAdResult") {
    strings("Load OX Ad, zoneId=")
}.point("reportNoDisplayAd") {
    field { owner(displayAdExistence.descriptor) }
}.next { invokeVirtual { paramCount(1); returns(Type.Void) } }

// Audio ads between tracks -----------------------------------------------------------------

private val audioAdContentProvider = klass("audioAdContentProvider") {
    sourceFile("AudioAdContentProvider.kt")
    strings("asset:///audioad/")
}

/** `AudioAdContentProvider.getAdSourceType(isLocal, isPremiumBoost, continuation)`. */
private val audioAdSourceType = method("audioAdSourceType") {
    inClass(audioAdContentProvider)
    paramCount(3)
    param(0, Type.Boolean)
    param(1, Type.Boolean)
    returns(Type.Object)
}

/** The sealed `AudioAdSourceType`, whose subclasses are Local, Network, and None. */
private val audioAdSourceTypeBase = klass("audioAdSourceTypeBase") {
    sourceFile("AudioAdSourceType.kt")
    custom { superclass == "Ljava/lang/Object;" }
}

/** `AudioAdSourceType.None`, the object the app returns when there is no ad to play. */
private val noAudioAd = fieldTarget("audioAdSourceType.None") {
    val none = bytecode.classesExtending(audioAdSourceTypeBase.descriptor)
        .singleOrNull { it.instanceFields.isEmpty() }
        ?: error("AudioAdSourceType.None not found")
    none.staticFields.singleOrNull { it.fieldType == none.descriptor }?.ref
        ?: error("${none.descriptor} has no instance field")
}

// Premium prompts --------------------------------------------------------------------------

private val premiumMeritType = klass("premiumMeritType") {
    enumConstants("MYLIST_CREATE_COUNT", "SEARCH_RESULT_SABI_MEDLEY", "SETTING_HIGH_QUALITY_AUDIO_CACHE")
}

/** `PremiumMeritLeadDialog.Companion.newInstance(requestCode, type)`. */
private val premiumLeadDialogFactory = method("premiumLeadDialogFactory") {
    strings("request_code", "request_data")
    params(Type.String, premiumMeritType.descriptor)
    returns(PREMIUM_MERIT_LEAD_DIALOG)
}

/** `show(fragmentManager, tag)` right after each `newInstance`: the seven Premium-only pop-ups. */
private val premiumLeadDialogShows = methods("premiumLeadDialogSites") {
    calls(premiumLeadDialogFactory)
}.points("premiumLeadDialogFactoryCall") {
    calls(premiumLeadDialogFactory)
}

private val premiumMeritLeadInfo = klass("premiumMeritLeadInfo") {
    sourceFile("PremiumMeritLeadInfo.kt")
    implements("java.io.Serializable")
}

/** `HomeFragment.showPremiumMeritLeadInfoBottomSheet(container, info)`, shown on its own at launch. */
private val homePremiumSheet = method("homePremiumSheet") {
    inClass(klass(HOME_FRAGMENT))
    paramCount(2)
    hasParam(premiumMeritLeadInfo.descriptor)
    returns(Type.Void)
}

private fun PatchRuntime.hideHighQualityAudioSnackbar() {
    val message = resources.id("string", "premium_merit_lead_message_watch_high_quality_audio")
        ?: error("the high-quality audio Premium message is gone")
    // Shown from the player whenever a track has a high-quality stream the user can't play. The
    // app reads the id from an R class field rather than a constant, so find the code by that field.
    val sites = bytecode.classes.asSequence()
        .flatMap { it.staticFields.asSequence() }
        .filter { it.fieldType == Type.Int && (it.initialValue as? EncodedVal.IntVal)?.field0 == message.toInt() }
        .flatMap { id ->
            methods("highQualityAudioSnackbar") { accesses { owner(id.classDescriptor); name(id.name) } }
                .all.map { it to id }
        }
        .toList()
    val (snackbar, id) = sites.singleOrNull()
        ?: error("expected one use of the high-quality audio message, found ${sites.size}")
    val unit = unitInstance(snackbar)
    gate(AdSettings.hidePremiumPrompts) {
        snackbar.point { field { owner(id.classDescriptor); name(id.name) } }
            .before { returnValue(staticField(unit)) }
    }
}

@Suppress("unused")
val adControls = patch("Ad controls") {
    description(
        """
        Adds switches to hide app-open ads, banner and in-feed ads, audio ads between tracks, and
        Premium pop-ups. Each one starts off, and is turned on in the Reseam patches screen, opened
        from the menu of VocaColle's Settings.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(
        vocacolleSettings,
        section(
            VocaColleStrings.sectionAds,
            AdSettings.hideAppOpenAds,
            AdSettings.hideDisplayAds,
            AdSettings.skipAudioAds,
            AdSettings.hidePremiumPrompts,
        ),
    )

    execute {
        gate(AdSettings.hideAppOpenAds) {
            appOpenAdAllowed.before {
                returnValue(callStatic("java.lang.Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", bool(false)))
            }
        }

        val setExistence = reportNoDisplayAd.callee()
        // The coroutine reaches the private LiveData through a synthetic accessor, so take the
        // controller's one field of the setter's type instead of following the call.
        val existence = klass(displayAdLoad.owner).fieldOfType(setExistence.owner)
        gate(AdSettings.hideDisplayAds) {
            displayAdLoad.before {
                thisObject.field(existence).call(setExistence, staticField(displayAdNone))
                returnVoid()
            }
        }

        // The provider is left to do its own setup and only its answer is swapped, at the returns
        // that hand back a source type. Returning early skipped that setup and left the player
        // stuck showing an ad that never loaded. A suspended call returns the coroutine marker
        // instead, which the type check leaves alone.
        gate(AdSettings.skipAudioAds) {
            audioAdSourceType.after {
                whenInstanceOf(result, audioAdSourceTypeBase.descriptor) {
                    returnValue(staticField(noAudioAd))
                }
            }
        }

        gate(AdSettings.hidePremiumPrompts) {
            premiumLeadDialogShows.forEach {
                next { invokeVirtual { paramCount(2); returns(Type.Void) } }.skip()
            }
            homePremiumSheet.alwaysReturn()
        }
        hideHighQualityAudioSnackbar()
    }
}
