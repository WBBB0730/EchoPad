package com.wbbb.echopad.mobile.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉菜单里的"发送剪贴板"磁贴：收起下拉菜单，打开透明页面读剪贴板并发出（ADR-0017）。 */
class SyncClipboardTileService : TileService() {
    /** 这是一个按钮，不是开关，一直显示成没打开。 */
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.run {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = SyncClipboardActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapseBeforeAndroid14(intent)
        }
    }

    /** Android 14 以下没有接受 PendingIntent 的版本，只能用这个已经废弃的。 */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseBeforeAndroid14(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
