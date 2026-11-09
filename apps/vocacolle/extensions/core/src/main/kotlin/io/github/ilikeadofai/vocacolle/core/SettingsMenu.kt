@file:JvmName("SettingsMenu")

package io.github.ilikeadofai.vocacolle.core

import android.view.Menu
import android.view.MenuItem
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.core.AppStrings

// Outside the range aapt assigns to app ids (0x7f......), so it can't collide with a VocaColle item.
private const val ITEM_ID = 0x0152_5E5A

/** Adds the Reseam entry to the overflow menu of VocaColle's own settings toolbar. */
@Export
fun addItem(menu: Menu?) {
    if (menu == null || menu.findItem(ITEM_ID) != null) return
    menu.add(Menu.NONE, ITEM_ID, Menu.CATEGORY_SECONDARY, AppStrings.menuTitle)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
}

@Export
fun isItem(item: MenuItem?): Boolean = item?.itemId == ITEM_ID

/** The title of the Reseam row added to VocaColle's settings list. */
@Export
fun rowTitle(): String = AppStrings.menuTitle
