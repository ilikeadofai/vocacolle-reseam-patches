package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.appEntry
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.library.bindings.FavoriteClick
import io.github.ilikeadofai.vocacolle.core.library.bindings.LocalFavorites
import io.github.ilikeadofai.vocacolle.settings.LibrarySettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

@Suppress("unused")
val localFavorites = patch("Local favorites") {
    description(
        """
        Adds favorites kept on this device only, with no limit: a ☆ Favorite button on a work's
        detail screen, and a list in the Reseam patches screen that plays them as a VocaColle
        playlist and exports or imports them as text. Nothing is sent, and the account's likes
        and mylists don't change.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(
        vocacolleSettings,
        section(VocaColleStrings.sectionLibrary, LibrarySettings.localFavorites, LibrarySettings.favoriteButton),
    )

    execute {
        appEntry { LocalFavorites.init(application) }
        onAction(LibrarySettings.localFavorites) { LocalFavorites.open(activity) }
        // Each MediaDetailData, with its id and title, for the button drawn from it.
        mediaDetailDataInit.after { LocalFavorites.rememberDetail(thisObject, param(0), param(1)) }
        implementComposeState(LocalFavorites.newState, LocalFavorites.readState, LocalFavorites.writeState)

        val (clickType, invoke) = buttonClick()
        FavoriteClick.target.classDef.addInterface(clickType)
        FavoriteClick.target.addMethod(invoke.name, invoke.proto) {
            LocalFavorites.toggle(thisObject.field(FavoriteClick.detail))
            returnNull()
        }

        val description = descriptionText.single()
        gate(LibrarySettings.favoriteButton) {
            // The screen reads its MediaDetailData just before the description. Its register is
            // reused before the button, so the extension holds it in between.
            description.previous { invokeVirtual { returns(mediaDetailData.descriptor) } }
                .next { resultOf(mediaDetailData.descriptor) }
                .captureAs("detail")
                .after { LocalFavorites.composingDetail(capture("detail")) }
            description.captureArgumentAs("composer", 7).after {
                call(
                    appButton,
                    nullObject, newInstance(FavoriteClick.descriptor, "(Ljava/lang/Object;)V", LocalFavorites.composedDetail()),
                    bool(true), long(BUTTON_COLOR), long(0), nullObject, nullObject,
                    LocalFavorites.buttonLabel(LocalFavorites.composedDetail()), nullObject, nullObject,
                    capture("composer"), int(0), int(BUTTON_DEFAULTS),
                )
            }
        }
    }
}
