package com.wbbb.echopad.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.SavedPairing
import com.wbbb.echopad.mobile.storage.StoredDesktop
import com.wbbb.echopad.mobile.storage.StoredPairing
import com.wbbb.echopad.mobile.touchpad.ScrollDirection
import com.wbbb.echopad.mobile.touchpad.TouchpadSettings
import com.wbbb.echopad.mobile.touchpad.TouchpadSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 手机端保存在本机的数据：配过的电脑端、当前电脑、手机端标识、触控板设置，共用一个 DataStore 文件。
 * 同一个文件在一个进程里只能有一个 DataStore，调用方需保证每个文件只创建一次。
 */
class LocalStorage private constructor(private val preferences: DataStore<Preferences>) {
    val pairedDesktops: PairedDesktopStore = object : PairedDesktopStore {
        override suspend fun load(): SavedPairing {
            val pairing = update { it }
            return SavedPairing(pairing.desktops.map { it.toPairedDesktop() }, pairing.active_id.ifEmpty { null })
        }

        override suspend fun save(desktop: PairedDesktop) {
            update { pairing ->
                pairing.copy(desktops = pairing.desktops.filter { it.id != desktop.id } + desktop.toStored())
            }
        }

        override suspend fun remove(id: String) {
            update { pairing ->
                pairing.copy(
                    desktops = pairing.desktops.filter { it.id != id },
                    active_id = pairing.active_id.takeIf { it != id }.orEmpty(),
                )
            }
        }

        override suspend fun setActive(id: String?) {
            update { it.copy(active_id = id.orEmpty()) }
        }

        @OptIn(ExperimentalUuidApi::class)
        override suspend fun phoneId(): String =
            update { pairing -> pairing.copy(phone_id = pairing.phone_id.ifEmpty { Uuid.random().toHexString() }) }.phone_id
    }

    /** 读出配对信息、改动后写回，返回改动后的结果。 */
    private suspend fun update(change: (StoredPairing) -> StoredPairing): StoredPairing {
        var updated = StoredPairing()
        preferences.edit { values ->
            removeSingleDesktopPairing(values)
            val current = values[PAIRING]?.let(StoredPairing.ADAPTER::decode) ?: StoredPairing()
            updated = change(current)
            if (updated != current) values[PAIRING] = updated.encode()
        }
        return updated
    }

    /** 以前的版本只保存一台电脑，而且没有证书指纹（ADR-0014），已经连不上了，要重新扫码。 */
    private fun removeSingleDesktopPairing(values: MutablePreferences) {
        LEGACY_KEYS.forEach { values.remove(it) }
    }

    /** 没设置过时速度都是 1 倍，滚动方向取 [TouchpadSettings] 的默认值。 */
    val touchpadSettings: Flow<TouchpadSettings> = preferences.data.map { values ->
        val defaults = TouchpadSettings()
        TouchpadSettings(
            speed = TouchpadSpeed(pointer = values[POINTER_SPEED] ?: 1f, scroll = values[SCROLL_SPEED] ?: 1f),
            twoFingerScroll = values[TWO_FINGER_SCROLL_NATURAL]?.toScrollDirection() ?: defaults.twoFingerScroll,
            stripScroll = values[STRIP_SCROLL_NATURAL]?.toScrollDirection() ?: defaults.stripScroll,
        )
    }

    suspend fun setTouchpadSettings(settings: TouchpadSettings) {
        preferences.edit { values ->
            values[POINTER_SPEED] = settings.speed.pointer
            values[SCROLL_SPEED] = settings.speed.scroll
            values[TWO_FINGER_SCROLL_NATURAL] = settings.twoFingerScroll == ScrollDirection.Natural
            values[STRIP_SCROLL_NATURAL] = settings.stripScroll == ScrollDirection.Natural
        }
    }

    companion object {
        private val PAIRING = byteArrayPreferencesKey("pairing")
        private val LEGACY_KEYS = listOf(
            stringPreferencesKey("desktop_host"),
            intPreferencesKey("desktop_port"),
            stringPreferencesKey("desktop_device_key"),
            stringPreferencesKey("desktop_name"),
            stringPreferencesKey("desktop_id"),
        )
        private val POINTER_SPEED = floatPreferencesKey("pointer_speed")
        private val SCROLL_SPEED = floatPreferencesKey("scroll_speed")
        /** 滚动方向存成是不是自然，不存枚举的名字，免得改名后读不出来。 */
        private val TWO_FINGER_SCROLL_NATURAL = booleanPreferencesKey("two_finger_scroll_natural")
        private val STRIP_SCROLL_NATURAL = booleanPreferencesKey("strip_scroll_natural")

        /** 数据保存在 [path] 指向的文件里。 */
        fun create(path: String): LocalStorage =
            LocalStorage(PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() }))
    }
}

private fun Boolean.toScrollDirection() = if (this) ScrollDirection.Natural else ScrollDirection.Traditional

private fun StoredDesktop.toPairedDesktop() = PairedDesktop(id, name, host, port, device_key, fingerprint)

private fun PairedDesktop.toStored() = StoredDesktop(id, name, host, port, deviceKey, fingerprint)
