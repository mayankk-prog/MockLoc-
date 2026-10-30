package com.example.mockloc

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to stop mocking when active, or open the app when idle. */
class MockTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        if (MockLocationService.running) {
            MockLocationService.stopRequested = true
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 1500)
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                startActivityAndCollapse(i)
            }
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = MockLocationService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Mock Loc"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (on) "On, tap to stop" else "Off, tap to open"
        }
        t.updateTile()
    }
}
