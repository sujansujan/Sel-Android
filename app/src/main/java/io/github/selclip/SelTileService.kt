package io.github.selclip

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast

class SelTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val targetIntent = if (SelAccessibilityService.instance != null) {
            Intent(this, ScreenGrabTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Toast.makeText(this, "Enable [sel] in Accessibility Settings", Toast.LENGTH_SHORT).show()
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                targetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { startActivityAndCollapse(pi) }
        } else {
            runCatching { startActivity(targetIntent) }
            SelAccessibilityService.instance?.triggerScreenGrab(delayMs = 350L)
        }
    }
}
