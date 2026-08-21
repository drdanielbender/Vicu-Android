package com.rendyhd.vicu.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R

/** Quick Settings action that opens Vicu's existing task-entry sheet. */
class QuickAddTileService : TileService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val binder = super.onBind(intent)
        Log.d(TAG, "onBind action=${intent?.action} connected=${binder != null}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val willRebind = super.onUnbind(intent)
        Log.d(TAG, "onUnbind action=${intent?.action} willRebind=$willRebind")
        return willRebind
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.d(TAG, "onRebind action=${intent?.action}")
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "onTileAdded")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")
        publishAvailableState("start listening")
    }

    override fun onStopListening() {
        Log.d(TAG, "onStopListening")
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick secure=$isSecure locked=$isLocked")

        // Refresh before dispatching the action. Besides keeping the presentation
        // deterministic across OEM SystemUI implementations, updateTile() also
        // acknowledges a successfully bound service to SystemUI.
        publishAvailableState("click")

        val launchTaskEntry = Runnable { openTaskEntry() }
        if (isSecure && isLocked) {
            Log.d(TAG, "Waiting for device unlock")
            unlockAndRun(launchTaskEntry)
        } else {
            launchTaskEntry.run()
        }
    }

    override fun onTileRemoved() {
        Log.d(TAG, "onTileRemoved")
        super.onTileRemoved()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun publishAvailableState(reason: String) {
        val tile = qsTile
        if (tile == null) {
            Log.w(TAG, "Cannot publish tile state ($reason): qsTile is null")
            return
        }

        try {
            tile.apply {
                state = Tile.STATE_ACTIVE
                icon = Icon.createWithResource(this@QuickAddTileService, R.drawable.ic_quick_add_tile)
                label = getString(R.string.quick_add_tile_label)
                contentDescription = getString(R.string.quick_add_tile_description)
                updateTile()
            }
            Log.d(TAG, "Published active tile state ($reason)")
        } catch (error: RuntimeException) {
            // A bad or stale SystemUI binding should not crash the application process.
            // The next standard-mode listening cycle gets another chance to recover.
            Log.e(TAG, "Failed to publish tile state ($reason)", error)
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

        try {
            TileServiceCompat.startActivityAndCollapse(this, pendingIntent)
            Log.d(TAG, "Task entry launch dispatched")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not launch task entry", error)
        }
    }

    private companion object {
        const val TAG = "QuickAddTile"
        const val TASK_ENTRY_REQUEST_CODE = 710_001
    }
}
