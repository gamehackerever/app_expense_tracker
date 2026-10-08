package com.expensetracker.offline.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class AddExpenseTileService : TileService() {
    override fun onClick() {
        super.onClick()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_ADD_EXPENSE", true)
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launchIntent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }
}