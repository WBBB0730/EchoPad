package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.protocol.ModifierKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class ModifierKeysTest {
    private val time = TestTimeSource()
    private val modifiers = ModifierKeys(time)

    private fun tap(key: ModifierKey) = modifiers.tap(key, DOUBLE_TAP)

    @Test
    fun aTappedModifierAppliesToTheNextActionOnly() {
        assertEquals(ModifierState.Latched, tap(ModifierKey.MODIFIER_KEY_META))

        assertEquals(listOf(ModifierKey.MODIFIER_KEY_META), modifiers.consume())
        assertEquals(emptyList(), modifiers.consume())
        assertFalse(modifiers.active)
    }

    @Test
    fun tappingAgainLaterCancelsIt() {
        tap(ModifierKey.MODIFIER_KEY_SHIFT)
        time += DOUBLE_TAP + 1.milliseconds

        assertEquals(null, tap(ModifierKey.MODIFIER_KEY_SHIFT))
        assertEquals(emptyList(), modifiers.consume())
    }

    @Test
    fun aDoubleTapLocksUntilTappedAgain() {
        tap(ModifierKey.MODIFIER_KEY_META)
        time += DOUBLE_TAP
        assertEquals(ModifierState.Locked, tap(ModifierKey.MODIFIER_KEY_META))

        assertEquals(listOf(ModifierKey.MODIFIER_KEY_META), modifiers.consume())
        assertEquals(listOf(ModifierKey.MODIFIER_KEY_META), modifiers.consume())

        time += DOUBLE_TAP * 10
        assertEquals(null, tap(ModifierKey.MODIFIER_KEY_META))
        assertEquals(emptyList(), modifiers.consume())
    }

    @Test
    fun aLongPressLocks() {
        modifiers.lock(ModifierKey.MODIFIER_KEY_ALT)

        assertEquals(listOf(ModifierKey.MODIFIER_KEY_ALT), modifiers.consume())
        assertEquals(listOf(ModifierKey.MODIFIER_KEY_ALT), modifiers.consume())
    }

    @Test
    fun latchedAndLockedModifiersCombineInAFixedOrder() {
        modifiers.lock(ModifierKey.MODIFIER_KEY_META)
        tap(ModifierKey.MODIFIER_KEY_SHIFT)
        tap(ModifierKey.MODIFIER_KEY_CONTROL)

        assertEquals(
            listOf(ModifierKey.MODIFIER_KEY_CONTROL, ModifierKey.MODIFIER_KEY_SHIFT, ModifierKey.MODIFIER_KEY_META),
            modifiers.consume(),
        )
        // 点亮的用完松开，锁定的保留。
        assertEquals(listOf(ModifierKey.MODIFIER_KEY_META), modifiers.consume())
    }

    @Test
    fun hidingTheRowReleasesEverything() {
        modifiers.lock(ModifierKey.MODIFIER_KEY_META)
        tap(ModifierKey.MODIFIER_KEY_SHIFT)

        modifiers.releaseAll()

        assertFalse(modifiers.active)
        assertEquals(emptyList(), modifiers.consume())
    }

    private companion object {
        val DOUBLE_TAP = 300.milliseconds
    }
}
