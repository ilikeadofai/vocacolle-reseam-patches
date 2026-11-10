# Native playlist integration

The user requested the original playlist control beside the heart, and explicitly asked to
continue implementation focused on playlists. Use VocaColle 7.48.0's existing LOCALLIST storage,
picker, and editing screens. Copy the patch's named JSON playlists into that storage using the
app's repository APIs. Preserve the JSON as an archival backup. Favorites and other library
features remain separate.

A button-only redirect would preserve two unrelated playlist systems. Injecting synthetic rows
into the native picker would require replacing native detail/edit operations too. Native local
storage is the coherent route: imported playlists work with the original picker, Library tab,
rename, delete, and playback without a parallel UI implementation.

Implementation choices: structural Reseam targets; generated bridges to the app's coroutine
runner, local-list creation, and item addition; one background worker; a journal scoped to native
library owner. Reserve a unique title before creating a list, persist its native ID immediately,
and deduplicate resumed additions against native storage. Mark completion only after all items
succeed. Completed transfers are not replayed, including after native rename/deletion. Do not
merge with unrelated same-name native lists or write to account mylists. Do not change login.

Reuse the player's existing bottom-sheet state to open the real picker from the patch's save
menu. Native edits become authoritative; retained JSON exports are explicitly backup snapshots.
If the native repository is unavailable, preserve the legacy UI. Native transfer may need normal
video metadata retrieval through the app when adding an ID; failed/offline additions remain
pending. Runtime validation must distinguish tested migration logic and emitted DEX from an
unobserved phone login/UI flow.
