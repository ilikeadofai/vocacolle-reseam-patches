package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.MethodTarget
import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.appEntry
import app.reseam.patch.before
import app.reseam.patch.enumConstant
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.patch
import app.reseam.patch.points
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.library.bindings.FavoriteClick
import io.github.ilikeadofai.vocacolle.core.library.bindings.LocalFavorites
import io.github.ilikeadofai.vocacolle.core.library.bindings.LocalLibrary
import io.github.ilikeadofai.vocacolle.core.library.bindings.PlaylistClick
import io.github.ilikeadofai.vocacolle.settings.LibrarySettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `NvMylist(id, isPublic, name, …)` and `NvCompactMylist(id, isPublic, name, …)`: mylists the app loaded. */
private val MYLIST_MODELS = listOf("NvMylist(id=", "NvCompactMylist(id=")

/** `NvApiException`, with its `NvApiErrorType`, thrown for nvapi errors. */
private val nvApiException = klass("NvApiException") { sourceFile("NvApiException.java") }
private val nvApiErrorType = klass("NvApiErrorType") { enumConstants("DAILY_LIMIT_EXCEEDED", "REACHED_LIMIT_BY_MYLIST", "FOLLOWING_LIMIT_EXCEEDED") }
private val followAddErrorType = klass("FollowAddErrorType") {
    sourceFile("FollowAddErrorType.kt")
    enumConstants("BLOCKED_BY_FOLLOWEE", "LIMIT_EXCEEDED")
}
private val followVerb = klass("FollowRequestHeader.Method") {
    sourceFile("FollowRequestHeader.kt")
    enumConstants("POST", "DELETE")
}

/** `POST /v1/users/me/likes/items`: (session, videoId, isShortsPlayer). */
private val likeAdd = method("likeAdd") {
    strings("/v1/users/me/likes/items")
    paramCount(3)
    returns(Type.Void)
}

/** `POST /v1/users/me/mylists/{id}/items`: (session, mylistId, itemId, description, isShortsPlayer). */
private val mylistItemAdd = method("mylistItemAdd") {
    strings("/v1/users/me/mylists/%d/items")
    paramCount(5)
}

/** `POST /v1/users/me/following/mylists/{id}`: (session, mylistId); unfollowing returns nothing. */
private val mylistFollow = method("mylistFollow") {
    strings("/v1/users/me/following/mylists/%d")
    paramCount(2)
    custom { returnType != Type.Void }
}

/**
 * Marks, at the request's start, what it is about, and at each place it rethrows the server's
 * error as the app's exception, hands that exception over: the extension keeps the item on the
 * device when the error is the account's limit.
 */
private fun PatchRuntime.keepOnLimit(label: String, request: MethodTarget, exception: String, mark: app.reseam.patch.CodeScope.() -> Unit) {
    request.before { mark() }
    val throws = request.points("$label error") {
        invoke { returns(exception) }
        then(within = 2) { opcode(app.reseam.patch.dex.Opcode.THROW) }
    }.all
    check(throws.isNotEmpty()) { "$label no longer rethrows the server's error" }
    throws.forEach { point ->
        point.previous { resultOf(exception) }.captureAs("error").after { LocalLibrary.failed(capture("error")) }
    }
}

@Suppress("unused")
val localFavorites = patch("Local favorites") {
    description(
        """
        Adds a library kept on this device only, with no limit: favorites, playlists, watched
        creators, and saved mylists, with ☆ Favorite and + Playlist buttons on a work's detail
        screen. When the server refuses a like, mylist item, or follow for the account's limit,
        the item is kept there instead. The account's likes, mylists, and follows don't change.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(
        vocacolleSettings,
        section(VocaColleStrings.sectionLibrary, LibrarySettings.localFavorites, LibrarySettings.favoriteButton, LibrarySettings.saveOnLimit),
    )

    execute {
        appEntry { LocalFavorites.init(application) }
        onAction(LibrarySettings.localFavorites) { LocalFavorites.open(activity) }
        // Each MediaDetailData, with its id, title, and owner, for the buttons drawn from it.
        mediaDetailDataInit.after { LocalFavorites.rememberDetail(thisObject, param(0), param(1), param(4), param(5)) }
        // Names for items kept by id only.
        playerVideoInfoInit.before { LocalLibrary.rememberTitle(param(0), param(1)) }
        MYLIST_MODELS.forEach { prefix ->
            val model = klass("mylistModel $prefix") { strings(prefix) }
            method("mylistModel.<init> $prefix") {
                inClass(model)
                custom { name == "<init>" && parameterTypes.size >= 3 && parameterTypes[0] == Type.Long && parameterTypes[2] == Type.String }
            }.after { LocalLibrary.rememberMylist(param(0), param(2)) }
        }

        // The server's error code, from either exception the requests throw.
        val apiException = nvApiException.descriptor
        val followException = klass("FollowAddApiException") {
            sourceFile("FollowAddApiException.kt")
            hasInstanceField(followAddErrorType.descriptor)
        }.descriptor
        LocalLibrary.errorCode.implement {
            whenInstanceOf(param(0), apiException) {
                returnValue(
                    param(0).cast(apiException).fieldOfType(nvApiErrorType.descriptor)
                        .callVirtual("java.lang.Enum", "name", "()Ljava/lang/String;"),
                )
            }
            whenInstanceOf(param(0), followException) {
                returnValue(
                    param(0).cast(followException).fieldOfType(followAddErrorType.descriptor)
                        .callVirtual("java.lang.Enum", "name", "()Ljava/lang/String;"),
                )
            }
            returnNull()
        }
        // Following a user and unfollowing take the same arguments; following sends POST.
        val post = followVerb.enumConstant("POST")
        val postOwner = post.owner
        val postName = post.name
        val userFollow = method("userFollow") {
            custom {
                classDef.sourceFile == "DefaultFollowUserService.kt" && parameterTypes.size == 4 && parameterTypes[0] == Type.Long
            }
            accesses { owner(postOwner); name(postName) }
        }
        keepOnLimit("likeAdd", likeAdd, apiException) { LocalLibrary.attemptLike(param(1)) }
        keepOnLimit("mylistItemAdd", mylistItemAdd, apiException) { LocalLibrary.attemptMylistItem(param(1), param(2)) }
        keepOnLimit("mylistFollow", mylistFollow, apiException) { LocalLibrary.attemptFollowMylist(param(1)) }
        keepOnLimit("userFollow", userFollow, followException) { LocalLibrary.attemptFollowUser(param(0)) }
        implementComposeState(LocalFavorites.newState, LocalFavorites.readState, LocalFavorites.writeState)

        val (clickType, invoke) = buttonClick()
        FavoriteClick.target.classDef.addInterface(clickType)
        FavoriteClick.target.addMethod(invoke.name, invoke.proto) {
            LocalFavorites.toggle(thisObject.field(FavoriteClick.detail))
            returnNull()
        }
        PlaylistClick.target.classDef.addInterface(clickType)
        PlaylistClick.target.addMethod(invoke.name, invoke.proto) {
            LocalFavorites.choosePlaylists(thisObject.field(PlaylistClick.detail))
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
                call(
                    appButton,
                    nullObject, newInstance(PlaylistClick.descriptor, "(Ljava/lang/Object;)V", LocalFavorites.composedDetail()),
                    bool(true), long(BUTTON_COLOR), long(0), nullObject, nullObject,
                    LocalFavorites.playlistLabel(LocalFavorites.composedDetail()), nullObject, nullObject,
                    capture("composer"), int(0), int(BUTTON_DEFAULTS),
                )
            }
        }
    }
}
