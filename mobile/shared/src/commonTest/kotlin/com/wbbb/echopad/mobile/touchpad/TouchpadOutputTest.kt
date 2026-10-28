package com.wbbb.echopad.mobile.touchpad

import com.wbbb.echopad.mobile.protocol.ButtonAction
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.MouseButton
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchpadOutputTest {
    private val sent = mutableListOf<ClientMessage>()
    private val output = TouchpadOutput { sent += it }

    @Test
    fun movementIsSentOncePerFrame() {
        output.handle(TouchpadAction.Move(1f, 0f, 100))
        output.handle(TouchpadAction.Move(1f, 0f, 100))
        assertEquals(emptyList(), sent)

        output.flush()
        val move = assertNotNull(sent.single().pointer_move)
        assertEquals(2.4f, move.dx, 0.001f)

        output.flush()
        assertEquals(1, sent.size)
    }

    @Test
    fun buttonsSendPendingMovementFirst() {
        output.handle(TouchpadAction.Move(1f, 0f, 100))
        output.handle(TouchpadAction.LeftClick)

        assertNotNull(sent[0].pointer_move)
        assertEquals(
            ButtonAction(button = MouseButton.MOUSE_BUTTON_LEFT, kind = ButtonAction.Kind.KIND_CLICK),
            sent[1].button_action,
        )
    }

    @Test
    fun speedSettingsScalePointerAndScroll() {
        output.speed = TouchpadSpeed(pointer = 2f, scroll = 0.5f)
        output.handle(TouchpadAction.Move(1f, 0f, 100))
        output.scroll(0f, 4f)
        output.flush()

        assertEquals(1.2f * 2, assertNotNull(sent[0].pointer_move).dx, 0.001f)
        assertEquals(4f * 2 * 0.5f, assertNotNull(sent[1].scroll).dy, 0.001f)
    }

    @Test
    fun dragsPressAndReleaseTheLeftButton() {
        output.handle(TouchpadAction.DragStart)
        output.handle(TouchpadAction.DragEnd)

        assertEquals(
            listOf(ButtonAction.Kind.KIND_PRESS, ButtonAction.Kind.KIND_RELEASE),
            sent.map { it.button_action?.kind },
        )
    }
}

class PointerAccelerationTest {
    @Test
    fun slowMovementStaysPrecise() {
        val (dx, dy) = PointerAcceleration.apply(1f, 0f, 100)
        assertEquals(1.2f, dx, 0.001f)
        assertEquals(0f, dy)
    }

    @Test
    fun fastMovementIsAmplifiedInTheSameDirection() {
        val (dx, dy) = PointerAcceleration.apply(-30f, 40f, 10)
        assertEquals(4.5f, dx / -30f, 0.001f)
        assertEquals(dx / -30f, dy / 40f, 0.001f)
    }
}

class ScrollInertiaTest {
    @Test
    fun inertiaSlowsDownAndStops() {
        val inertia = ScrollInertia(0f, 2f)
        val first = assertNotNull(inertia.step(16))
        assertEquals(32f, first.second, 0.001f)

        var previous = first.second
        var steps = 0
        while (true) {
            val (_, dy) = inertia.step(16) ?: break
            assertTrue(abs(dy) < abs(previous))
            previous = dy
            steps++
        }
        assertTrue(steps in 10..200)
        assertNull(inertia.step(16))
    }
}
