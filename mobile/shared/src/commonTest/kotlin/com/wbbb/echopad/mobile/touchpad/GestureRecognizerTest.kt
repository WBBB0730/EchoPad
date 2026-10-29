package com.wbbb.echopad.mobile.touchpad

import com.wbbb.echopad.mobile.touchpad.TouchpadAction.DragEnd
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.DragStart
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Fling
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.LeftClick
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Move
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.RightClick
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Scroll
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Zoom
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.ZoomEnd
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.ZoomStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GestureRecognizerTest {
    private val recognizer = GestureRecognizer()

    /** 一次触摸事件里只移动了一根手指。 */
    private fun GestureRecognizer.move(id: Long, x: Float, y: Float, time: Long) = move(mapOf(id to TouchPoint(x, y)), time)

    @Test
    fun aTapClicksAsSoonAsTheFingerLifts() {
        assertEquals(emptyList(), recognizer.down(1, 0f, 0f, 0))
        assertEquals(listOf(LeftClick), recognizer.up(1, 0f, 0f, 50))
        assertNull(recognizer.deadline)
    }

    @Test
    fun twoQuickTapsClickTwiceForTheDesktopToJoinIntoADoubleClick() {
        recognizer.down(1, 0f, 0f, 0)
        assertEquals(listOf(LeftClick), recognizer.up(1, 0f, 0f, 50))
        recognizer.down(2, 1f, 1f, 150)
        assertEquals(listOf(LeftClick), recognizer.up(2, 1f, 1f, 200))
    }

    @Test
    fun holdingStillStartsADrag() {
        recognizer.down(1, 0f, 0f, 0)
        assertEquals(GestureRecognizer.HOLD_TIMEOUT, recognizer.deadline)
        assertEquals(emptyList(), recognizer.timeout(100))

        assertEquals(listOf(DragStart), recognizer.timeout(GestureRecognizer.HOLD_TIMEOUT))
        assertNull(recognizer.deadline)
        assertEquals(listOf(Move(10f, 0f, 16)), recognizer.move(1, 10f, 0f, GestureRecognizer.HOLD_TIMEOUT + 16))
        assertEquals(listOf(DragEnd), recognizer.up(1, 10f, 0f, 300))
    }

    @Test
    fun movingBeforeTheHoldTimeoutNeverDrags() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.move(1, 20f, 0f, 100)

        assertNull(recognizer.deadline)
        assertEquals(emptyList(), recognizer.timeout(GestureRecognizer.HOLD_TIMEOUT))
        assertEquals(listOf(Move(10f, 0f, 200)), recognizer.move(1, 30f, 0f, 300))
        assertEquals(emptyList(), recognizer.up(1, 30f, 0f, 310))
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

        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(mapOf(1L to TouchPoint(0f, 10f), 2L to TouchPoint(10f, 10f)), 26))
        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(mapOf(1L to TouchPoint(0f, 20f), 2L to TouchPoint(10f, 20f)), 42))
        assertEquals(listOf(Fling(0f, 1.25f)), recognizer.up(1, 0f, 20f, 50))
        assertEquals(emptyList(), recognizer.move(2, 10f, 60f, 60))
        assertEquals(emptyList(), recognizer.up(2, 10f, 60f, 70))
    }

    @Test
    fun aSidewaysTwoFingerSlideScrollsInsteadOfZooming() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 100f, 0f, 10)

        // 两根手指在同一次事件里一起移动，距离不变。
        assertEquals(listOf(Scroll(20f, 0f)), recognizer.move(mapOf(1L to TouchPoint(20f, 0f), 2L to TouchPoint(120f, 0f)), 26))
    }

    @Test
    fun aScrollStaysAScrollWhenTheFingersDrift() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 100f, 0f, 10)

        assertEquals(listOf(Scroll(0f, 20f)), recognizer.move(mapOf(1L to TouchPoint(0f, 20f), 2L to TouchPoint(100f, 20f)), 26))
        assertEquals(listOf(Scroll(0f, 20f)), recognizer.move(mapOf(1L to TouchPoint(-10f, 40f), 2L to TouchPoint(110f, 40f)), 42))
    }

    @Test
    fun spreadingAndPinchingTwoFingersZooms() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 100f, 0f, 10)

        assertEquals(listOf(ZoomStart, Zoom(1.2f)), recognizer.move(mapOf(1L to TouchPoint(-10f, 0f), 2L to TouchPoint(110f, 0f)), 26))
        // 捏回原来的距离，倍数乘起来正好回到原大小。
        assertEquals(listOf(Zoom(100f / 120f)), recognizer.move(mapOf(1L to TouchPoint(0f, 0f), 2L to TouchPoint(100f, 0f)), 42))
        // 很快捏完也不是右键。
        assertEquals(listOf(ZoomEnd), recognizer.up(1, 0f, 0f, 50))
        assertEquals(emptyList(), recognizer.up(2, 100f, 0f, 60))
    }

    @Test
    fun aScrollLedByOneFingerIsNotMistakenForAZoom() {
        // 两根手指上下排着往下滑，上面那根先动，两指距离先变小。
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 0f, 100f, 10)

        assertEquals(emptyList(), recognizer.move(1, 0f, 10f, 26))
        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(mapOf(1L to TouchPoint(0f, 20f), 2L to TouchPoint(0f, 110f)), 42))
    }

    @Test
    fun movingOnlyOneFingerScrollsInsteadOfZooming() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 100f, 0f, 10)

        assertEquals(emptyList(), recognizer.move(2, 120f, 0f, 26))
        assertEquals(listOf(Scroll(5f, 0f)), recognizer.move(2, 130f, 0f, 42))
    }

    @Test
    fun aThirdFingerEndsTheZoom() {
        recognizer.down(1, 0f, 0f, 0)
        recognizer.down(2, 100f, 0f, 10)
        recognizer.move(mapOf(1L to TouchPoint(-10f, 0f), 2L to TouchPoint(110f, 0f)), 26)

        assertEquals(listOf(ZoomEnd), recognizer.down(3, 50f, 50f, 40))
        assertEquals(emptyList(), recognizer.move(2, 150f, 0f, 56))
        assertEquals(emptyList(), recognizer.up(1, 0f, 0f, 60))
        assertEquals(emptyList(), recognizer.up(2, 150f, 0f, 65))
        assertEquals(emptyList(), recognizer.up(3, 50f, 50f, 70))
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
