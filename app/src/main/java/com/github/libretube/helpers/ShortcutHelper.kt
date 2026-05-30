package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.TopLevelDestination
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ShortcutHelper {

    private fun createShortcut(context: Context, appShortcut: TopLevelDestination): ShortcutInfoCompat {
        val label = context.getString(appShortcut.label)
        return ShortcutInfoCompat.Builder(context, appShortcut.route)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, appShortcut.icon))
            .setIntent(
                Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java)
                    .putExtra(IntentData.fragmentToOpen, appShortcut.route)
            )
            .build()
    }

    fun createShortcuts(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty()) {
                    val maxShortcutCount =
                        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                    val dynamicShortcuts = TopLevelDestination.entries
                        .take(maxShortcutCount)
                        .map { createShortcut(context, it) }

                    ShortcutManagerCompat.setDynamicShortcuts(context, dynamicShortcuts)
                }
            } catch (e: Exception) {
                Log.w("ShortcutHelper", "Failed to create dynamic shortcuts", e)
            }
        }
    }
}
