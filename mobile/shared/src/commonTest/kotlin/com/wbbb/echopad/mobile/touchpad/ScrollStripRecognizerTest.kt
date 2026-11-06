package com.wbbb.echopad.mobile.touchpad

import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Fling
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Move
import com.wbbb.echopad.mobile.touchpad.TouchpadAction.Scroll
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollStripRecognizerTest {
    private val recognizer = ScrollStripRecognizer()

    @Test
    fun slidingUpOrDownScrollsVerticallyOnlyAfterLeavingTheTapSlop() {
        recognizer.down(0f, 0f, 0)

        assertEquals(emptyList(), recognizer.move(0f, 5f, 10))
        // 默认是自然方向：内容跟着手指走。
        assertEquals(listOf(Scroll(0f, 5f)), recognizer.move(0f, 10f, 20))
        // 判定为滚动后，左右的位移不滚动，也不移动指针。
        assertEquals(listOf(Scroll(0f, -15f)), recognizer.move(30f, -5f, 30))
    }

    @Test
    fun aMostlyVerticalDiagonalStillScrolls() {
        recognizer.down(0f, 0f, 0)

        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(6f, 10f, 16))
    }

    @Test
    fun startingSidewaysMovesThePointerInstead() {
        recognizer.down(0f, 0f, 0)

        // 超出轻点范围的这次移动只用来判定，下一次移动才移动指针，和触控区一样。
        assertEquals(emptyList(), recognizer.move(-10f, 2f, 16))
        assertEquals(listOf(Move(-10f, 8f, 16)), recognizer.move(-20f, 10f, 32))
        // 之后上下滑也一直移动指针；快速滑动后离开不产生惯性，也不单击。
        assertEquals(listOf(Move(0f, 20f, 16)), recognizer.move(-20f, 30f, 48))
        assertEquals(emptyList(), recognizer.up(-20f, 30f, 56))
    }

    @Test
    fun aTapDoesNothing() {
        recognizer.down(0f, 0f, 0)

        assertEquals(emptyList(), recognizer.move(3f, 3f, 20))
        assertEquals(emptyList(), recognizer.up(3f, 3f, 40))
    }

    @Test
    fun aQuickSlideFlings() {
        recognizer.down(0f, 0f, 0)

        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(0f, 10f, 16))
        assertEquals(listOf(Scroll(0f, 10f)), recognizer.move(0f, 20f, 32))
        assertEquals(listOf(Fling(0f, 1.25f)), recognizer.up(0f, 20f, 40))
    }

    @Test
    fun traditionalDirectionScrollsAndFlingsAgainstTheFinger() {
        val recognizer = ScrollStripRecognizer(ScrollDirection.Traditional)
        recognizer.down(0f, 0f, 0)

        // 传统方向和电脑上的滚动条一样：手指往下，页面往下翻。
        assertEquals(listOf(Scroll(0f, -10f)), recognizer.move(0f, 10f, 16))
        assertEquals(listOf(Scroll(0f, -10f)), recognizer.move(0f, 20f, 32))
        assertEquals(listOf(Fling(0f, -1.25f)), recognizer.up(0f, 20f, 40))
    }

    @Test
    fun pausingBeforeLiftingDoesNotFling() {
        recognizer.down(0f, 0f, 0)
        recognizer.move(0f, 20f, 16)

        assertEquals(emptyList(), recognizer.up(0f, 20f, 200))
    }

    @Test
    fun eachTouchDecidesAgain() {
        recognizer.down(0f, 0f, 0)
        recognizer.move(20f, 0f, 16)
        recognizer.up(20f, 0f, 200)

        recognizer.down(0f, 100f, 300)
        assertEquals(listOf(Scroll(0f, 20f)), recognizer.move(0f, 120f, 316))
    }
}
