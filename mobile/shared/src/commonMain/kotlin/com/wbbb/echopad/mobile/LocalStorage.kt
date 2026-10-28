package com.wbbb.echopad.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.touchpad.TouchpadSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * 手机端保存在本机的数据：已配对的电脑端、指针速度和滚动速度，共用一个 DataStore 文件。
 * 同一个文件在一个进程里只能有一个 DataStore，调用方需保证每个文件只创建一次。
 */
class LocalStorage private constructor(private val preferences: DataStore<Preferences>) {
    val pairedDesktops: PairedDesktopStore = object : PairedDesktopStore {
        override suspend fun load(): PairedDesktop? {
            val values = preferences.data.first()
            return PairedDesktop(
                host = values[HOST] ?: return null,
                port = values[PORT] ?: return null,
                deviceKey = values[DEVICE_KEY] ?: return null,
                name = values[NAME].orEmpty(),
            )
        }

        override suspend fun save(desktop: PairedDesktop) {
            preferences.edit { values ->
                values[HOST] = desktop.host
                values[PORT] = desktop.port
                values[DEVICE_KEY] = desktop.deviceKey
                values[NAME] = desktop.name
            }
        }

        override suspend fun clear() {
            preferences.edit { values ->
                values.remove(HOST)
                values.remove(PORT)
                values.remove(DEVICE_KEY)
                values.remove(NAME)
            }
        }
    }

    /** 没设置过时都是 1 倍。 */
    val touchpadSpeed: Flow<TouchpadSpeed> = preferences.data.map { values ->
        TouchpadSpeed(pointer = values[POINTER_SPEED] ?: 1f, scroll = values[SCROLL_SPEED] ?: 1f)
    }

    suspend fun setTouchpadSpeed(speed: TouchpadSpeed) {
        preferences.edit { values ->
            values[POINTER_SPEED] = speed.pointer
            values[SCROLL_SPEED] = speed.scroll
        }
    }

    companion object {
        private val HOST = stringPreferencesKey("desktop_host")
        private val PORT = intPreferencesKey("desktop_port")
        private val DEVICE_KEY = stringPreferencesKey("desktop_device_key")
        private val NAME = stringPreferencesKey("desktop_name")
        private val POINTER_SPEED = floatPreferencesKey("pointer_speed")
        private val SCROLL_SPEED = floatPreferencesKey("scroll_speed")

        /** 数据保存在 [path] 指向的文件里。 */
        fun create(path: String): LocalStorage =
            LocalStorage(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
    }
}
