package com.wbbb.echopad.mobile.touchpad

import com.wbbb.echopad.mobile.touchpad.TouchpadAction.DoubleClick
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.DragEnd
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.DragStart
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Fling
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.LeftClick
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Move
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.RightClick
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Scroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GestureRecognizerTest {
    private val recognizer = GestureRecognizer()

    @Test
    fun aTapClicksOnceTheDoubleTapWindowPasses() {
        assertEquals(emptyList(), recognizer.down(1, 0f, 0f, 0))
        assertEquals(emptyList(), recognizer.up(1, 0f, 0f, 50))
        assertEquals(50 + GestureRecognizer.DOUBLE_TAP_WINDOW, recognizer.deadline)

        assertEquals(emptyList(), recognizer.timeout(100))
        assertEquals(listOf(LeftClick), recognizer.timeout(50 + GestureRecognizer.DOUBLE_TAP_WINDOW))
        assertNull(recognizer.deadline)
    }

    @Test
    fun twoQuickTapsDoubleClick() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.up(1, 0f, 0f, 50)
        recognizer.down(2, 1f, 1f, 150)

        assertEquals(listOf(DoubleClick), recognizer.up(2, 1f, 1f, 200))
        assertEquals(emptyList(), recognizer.timeout(1_000))
    }

    @Test
    fun tapThenHoldAndMoveDrags() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.up(1, 0f, 0f, 50)
        recognizer.down(2, 0f, 0f, 150)

        assertEquals(listOf(DragStart), recognizer.move(2, 20f, 0f, 200))
        assertEquals(listOf(Move(10f, 0f, 16)), recognizer.move(2, 30f, 0f, 216))
        assertEquals(listOf(DragEnd), recognizer.up(2, 30f, 0f, 230))
    }

    @Test
    fun slidingMovesThePointerOnlyAfterLeavingTheTapSlop() {
        recognizer.down(1, 0f, 0f, 0)

        assertEquals(emptyList(), recognizer.move(1, 5f, 0f, 10))
        assertEquals(emptyList(), recognizer.move(1, 10f, 0f, 20))
        assertEquals(listOf(Move(5f, 0f, 16)), recognizer.move(1, 15f, 0f, 36))
        assertEquals(emptyList(), recognizer.up(1, 15f, 0f, 40))
        assertNull(recognizer.deadline)
    }

    @Test
    fun aTwoFingerTapRightClicks() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 40f, 0f, 10)

        assertEquals(listOf(RightClick), recognizer.up(1, 0f, 0f, 100))
        assertEquals(emptyList(), recognizer.up(2, 40f, 0f, 110))
    }

    @Test
    fun aTwoFingerSlideScrollsAndFlings() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 10f, 0f, 10)

        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(1, 0f, 20f, 26))
        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(2, 10f, 20f, 42))
        assertEquals(listOf(Fling(0f, 1.25f)), recognizer.up(1, 0f, 20f, 50))
        assertEquals(emptyList(), recognizer.move(2, 10f, 60f, 60))
        assertEquals(emptyList(), recognizer.up(2, 10f, 60f, 70))
    }

    @Test
    fun threeFingerGesturesAreIgnored() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 10f, 0f, 5)
        recognizer.down(3, 20f, 0f, 10)

        assertEquals(emptyList(), recognizer.move(1, 0f, 50f, 30))
        assertEquals(emptyList(), recognizer.up(1, 0f, 50f, 40))
        assertEquals(emptyList(), recognizer.up(2, 10f, 0f, 45))
        assertEquals(emptyList(), recognizer.up(3, 20f, 0f, 50))
        assertNull(recognizer.deadline)
    }
}
