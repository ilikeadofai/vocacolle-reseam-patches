package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.ClassTarget
import app.reseam.patch.FieldTarget
import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.enumConstant
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.methods
import app.reseam.patch.point
import app.reseam.patch.points
import io.github.ilikeadofai.vocacolle.core.library.bindings.AddNativePlaylistItem
import io.github.ilikeadofai.vocacolle.core.library.bindings.CreateNativePlaylist
import io.github.ilikeadofai.vocacolle.core.library.bindings.NativePlaylists

private val nativeRepository = klass("nativePlaylistRepository") { sourceFile("PlaylistInLibraryRepositoryInterface.kt") }
private val nativeCached = klass("nativeCachedPlaylistService") {
    sourceFile("CachedPlaylistService.kt")
    custom { !descriptor.contains('$') }
}
private val nativeKind = klass("nativePlaylistType") { enumConstants("LOCALLIST", "MYLIST", "WATCH_LATER") }
private val nativeVideoId = klass("nativeVideoIdSource") { strings("VideoIdSource(videoId=") }
private val nativeWrapper = klass("nativeMediaWrapper") {
    sourceFile("MediaWrapper.kt")
    custom { !descriptor.contains('$') }
}
private val nativeCreate = method("nativeCreateLocalPlaylist") {
    inClass(nativeRepository)
    custom { parameterTypes.size == 4 && parameterTypes[0] == Type.String &&
        parameterTypes[1] == nativeKind.descriptor && parameterTypes[2] == Type.Boolean }
}
private val nativeAdd = method("nativeAddLocalPlaylistItem") {
    inClass(nativeRepository)
    custom { parameterTypes.size == 3 && parameterTypes[0] == nativeWrapper.descriptor && parameterTypes[1] == Type.Long }
}
private val cachedGetter = method("nativeCachedPlaylistGetter") { inClass(nativeRepository); params(); returns(nativeCached.descriptor) }
private val nativeOwner = method("nativeLibraryOwner") { inClass(nativeRepository); params(); returns(Type.Long) }
private val nativeRepositoryGetter = method("nativePlaylistRepositoryGetter") {
    inClass(klass("jp.nicovideo.nicobox.ui.NicoboxApplication"))
    params(); returns(nativeRepository.descriptor)
}
private val nativeLists = methods("nativeDefaultPlaylistTitles") {
    custom { classDef.sourceFile == "PlaylistCreateViewModel.kt" }
}.points("nativeExistingPlaylists") {
    invokeVirtual { owner(nativeCached.descriptor); params(); returns(Type.List) }
}.single().callee("nativeExistingPlaylistGetter")
private val nativeEntry = klass("nativePlaylistInLibrary") { strings("PlaylistInLibrary(ownerPlaylist=") }
private val nativeOwnerPlaylist = klass("nativeOwnerPlaylist") { strings("OwnerPlaylist(id=") }
private val nativePlaylistWithOwner = klass("nativePlaylistWithOwner") { strings("PlaylistWithOwner(playlist=") }
private val nativePlaylist = klass("nativePlaylist") { sourceFile("Playlist.kt"); strings("Playlist(id=") }
private val nativeItemState = klass("nativePlaylistItemState") { strings("PlaylistItemWithMediaCacheState(item=") }
private val nativeItem = klass("nativePlaylistItem") { sourceFile("PlaylistItem.kt"); strings("PlaylistItem(id=") }

/** Read the same stable property that this data class prints under its unambiguous label. */
private fun printedField(model: ClassTarget, label: String, type: String): FieldTarget {
    val text = method("${model.descriptor} printed fields") {
        inClass(model)
        custom { name == "toString" }
    }.point("${model.descriptor} $label") { string(label) }
    // R8 loads the fields before building the string; follow the append argument back to its load.
    val value = if (type == Type.String) text.next {
        invokeVirtual { owner("java.lang.StringBuilder"); name("append"); params(Type.String) }
    } else text
    val field = value.next {
        invokeVirtual { owner("java.lang.StringBuilder"); name("append"); params(if (type == Type.List) Type.Object else type) }
    }.writer(1).field()
    check(field.owner == model.descriptor && field.type == type) { "Native playlist property changed: $label" }
    return field
}

/** Suspend lambdas handed to the app's own runBlocking, invoked only by our worker thread. */
internal fun PatchRuntime.installNativePlaylists() {
    val runBlocking = method("nativeRunBlockingDefault") {
        custom { classDef.sourceFile == "Builders.kt" && returnType == Type.Object &&
            parameterTypes.size == 4 && parameterTypes[2] == Type.Int && parameterTypes[3] == Type.Object }
    }
    val function2 = bytecode.findClass(runBlocking.parameterTypes[1]) ?: error("native coroutine callback is missing")
    val invoke = function2.virtualMethods.singleOrNull { it.parameterTypes.size == 2 }
        ?: error("native coroutine callback is no longer a Function2")
    check(function2.isInterface && invoke.returnType == Type.Object)
    check(nativeCreate.parameterTypes.last() == nativeAdd.parameterTypes.last()) { "native continuations differ" }
    val continuation = nativeCreate.parameterTypes.last()
    val local = nativeKind.enumConstant("LOCALLIST")
    NativePlaylists.ownerId.implement { returnValue(param(0).cast(nativeRepository.descriptor).call(nativeOwner)) }
    NativePlaylists.entries.implement {
        returnValue(param(0).cast(nativeRepository.descriptor).call(cachedGetter).call(nativeLists))
    }
    val ownerId = printedField(nativeOwnerPlaylist, "OwnerPlaylist(id=", Type.Long)
    val itemRows = printedField(nativeEntry, ", itemWithMediaCacheStates=", Type.List)
    val videoId = printedField(nativeItem, ", mediaId=", Type.String)
    NativePlaylists.entryId.implement {
        returnValue(param(0).cast(nativeEntry.descriptor).fieldOfType(nativeOwnerPlaylist.descriptor).field(ownerId))
    }
    NativePlaylists.entryTitle.implement {
        returnValue(param(0).cast(nativeEntry.descriptor).fieldOfType(nativePlaylistWithOwner.descriptor)
            .fieldOfType(nativePlaylist.descriptor).fieldOfType(Type.String))
    }
    NativePlaylists.entryType.implement {
        returnValue(param(0).cast(nativeEntry.descriptor).fieldOfType(nativePlaylistWithOwner.descriptor)
            .fieldOfType(nativePlaylist.descriptor).fieldOfType(nativeKind.descriptor)
            .callVirtual("java.lang.Enum", "name", "()Ljava/lang/String;"))
    }
    NativePlaylists.entryItems.implement { returnValue(param(0).cast(nativeEntry.descriptor).field(itemRows)) }
    NativePlaylists.itemVideoId.implement {
        returnValue(param(0).cast(nativeItemState.descriptor).fieldOfType(nativeItem.descriptor).field(videoId))
    }
    CreateNativePlaylist.target.classDef.addInterface(function2.descriptor)
    CreateNativePlaylist.target.addMethod(invoke.name, invoke.proto) {
        returnValue(thisObject.fieldOfType(Type.Object).cast(nativeRepository.descriptor).call(
            nativeCreate, thisObject.fieldOfType(Type.String), staticField(local), bool(true), param(1).cast(continuation),
        ))
    }
    AddNativePlaylistItem.target.classDef.addInterface(function2.descriptor)
    AddNativePlaylistItem.target.addMethod(invoke.name, invoke.proto) {
        returnValue(thisObject.fieldOfType(Type.Object).cast(nativeRepository.descriptor).call(
            nativeAdd, newInstance(nativeVideoId.descriptor, "(Ljava/lang/String;)V", thisObject.fieldOfType(Type.String)),
            thisObject.fieldOfType(Type.Long), param(1).cast(continuation),
        ))
    }
    NativePlaylists.runOperation.implement {
        returnValue(call(runBlocking, nullObject, param(0).cast(function2.descriptor), int(1), nullObject))
    }
    nativeRepositoryGetter.after { NativePlaylists.repositoryReady(result) }

    // The original picker and the same host that the player uses for its playlist icon.
    val picker = method("nativePlaylistPickerShow") {
        custom { classDef.sourceFile == "PlaylistSelectBottomSheetDelegate.kt" &&
            parameterTypes.size == 6 && returnType == Type.Void }
    }
    val addRequest = klass("nativePlaylistAddRequest") {
        sourceFile("PlaylistSelectRequest.kt")
        hasInstanceField(nativeWrapper.descriptor)
    }
    val source = klass("nativePlaylistSource") {
        sourceFile("PlaylistSelectSourceFunction.kt")
        enumConstants("WATCH", "OPTION_MENU", "PLAYLIST_EDIT", "PLAYBACK_LIST")
    }
        .enumConstant("OPTION_MENU")
    NativePlaylists.showPicker.implement {
        val request = newInstance(addRequest.descriptor, "(${nativeWrapper.descriptor}Ljava/lang/Long;${source.type})V",
            newInstance(nativeVideoId.descriptor, "(Ljava/lang/String;)V", param(1)), nullObject, staticField(source))
        newInstance(picker.owner, "()V").call(picker, param(0).cast(picker.parameterTypes[0]), request,
            nullObject, nullObject, nullObject, nullObject)
        returnVoid()
    }
    val player = klass("jp.nicovideo.nicobox.ui.player.PlayerFragment")
    methods("nativePlayerScreen") { inClass(player) }
        .points("nativePlayerSheetHost") {
            invoke { returns(picker.parameterTypes[0]); paramCount(2) }
        }.single().next { resultOf(picker.parameterTypes[0]) }.captureAs("nativePlaylistHost").after {
            NativePlaylists.rememberPicker(capture("nativePlaylistHost"))
        }
    method("nativePlayerDestroyed") {
        inClass(player)
        strings("PlayerFragment.onDestroy() call: #")
    }.after { NativePlaylists.clearPicker() }
}
