package com.rendyhd.vicu.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R

/** Quick Settings action that opens Vicu's existing task-entry sheet. */
class QuickAddTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.quick_add_tile_label)
            contentDescription = getString(R.string.quick_add_tile_description)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        val launchTaskEntry = Runnable { openTaskEntry() }
        if (isSecure && isLocked) {
            unlockAndRun(launchTaskEntry)
        } else {
            launchTaskEntry.run()
        }
    }

    private fun openTaskEntry() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SHOW_TASK_ENTRY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntentActivityWrapper(
            this,
            TASK_ENTRY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )
        TileServiceCompat.startActivityAndCollapse(this, pendingIntent)
    }

    private companion object {
        const val TASK_ENTRY_REQUEST_CODE = 710_001
    }
}
