package com.wbbb.echopad.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wbbb.echopad.mobile.dictation.DictationMode
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/**
 * 手机端保存在本机的数据：已配对的电脑端和上屏方式，共用一个 DataStore 文件。
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

    /** 没选过时是直接上屏。 */
    val dictationMode: Flow<DictationMode> = preferences.data.map { values ->
        DictationMode.entries.find { it.name == values[DICTATION_MODE] } ?: DictationMode.Direct
    }

    suspend fun setDictationMode(mode: DictationMode) {
        preferences.edit { it[DICTATION_MODE] = mode.name }
    }

    companion object {
        private val HOST = stringPreferencesKey("desktop_host")
        private val PORT = intPreferencesKey("desktop_port")
        private val DEVICE_KEY = stringPreferencesKey("desktop_device_key")
        private val NAME = stringPreferencesKey("desktop_name")
        private val DICTATION_MODE = stringPreferencesKey("dictation_mode")

        /** 数据保存在 [path] 指向的文件里。 */
        fun create(path: String): LocalStorage =
            LocalStorage(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
    }
}
