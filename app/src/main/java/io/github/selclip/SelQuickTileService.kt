package io.github.selclip

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.selclip.data.PrefsStore

/**
 * Lightweight Android Quick Settings tile for 1-tap Pause / Resume of Sel's automatic popup bar.
 * Allocates nothing at idle; wakes up only when the user pulls down the notification shade.
 */
class SelQuickTileService : TileService() {

    companion object {
        fun requestSync(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SelQuickTileService::class.java)
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTileUi()
    }

    override fun onClick() {
        super.onClick()
        val service = SelAccessibilityService.instance
        if (service == null) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                runCatching { startActivityAndCollapse(pi) }
            } else {
                @Suppress("DEPRECATION")
                runCatching { startActivityAndCollapse(intent) }
            }
            return
        }

        val nowPaused = !PrefsStore.isServicePaused(this)
        PrefsStore.setServicePaused(this, nowPaused)
        service.onPausedStateChanged(nowPaused)
        syncTileUi()
    }

    private fun syncTileUi() {
        val tile = qsTile ?: return
        val active = SelAccessibilityService.instance != null
        val paused = PrefsStore.isServicePaused(this)

        tile.label = getString(R.string.qs_tile_label)
        when {
            !active -> {
                tile.state = Tile.STATE_UNAVAILABLE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Service Off"
            }
            paused -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Paused"
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Active"
            }
        }
        tile.updateTile()
    }
}
