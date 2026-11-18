package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.FakeFinder
import com.wbbb.echopad.mobile.connection.FakeSession
import com.wbbb.echopad.mobile.connection.FakeTransport
import com.wbbb.echopad.mobile.connection.MemoryStore
import com.wbbb.echopad.mobile.connection.pairedDesktop
import com.wbbb.echopad.mobile.protocol.CharacterPress
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ModifierKey
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.protocol.TypeText
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardInputTest {
    private val transport = FakeTransport()

    /** 已连接的手机键盘，以及电脑端这一侧。 */
    private suspend fun TestScope.connectedKeyboard(): Pair<KeyboardInput, FakeSession> {
        val store = MemoryStore(pairedDesktop())
        val connection = DesktopConnection(store, "Phone", transport, backgroundScope, FakeFinder(), MutableSharedFlow())
        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
        return KeyboardInput(connection) to session
    }

    private fun TestScope.sent(session: FakeSession): List<ClientMessage> {
        runCurrent()
        return generateSequence { session.sent.tryReceive().getOrNull() }.map(ClientMessage.ADAPTER::decode).toList()
    }

    private fun text(text: String) = ClientMessage(type_text = TypeText(text = text))

    private fun key(key: KeyPress.Key) = ClientMessage(key_press = KeyPress(key = key))

    @Test
    fun composingTextStaysOnThePhoneUntilCommitted() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.compose("ni")
        keyboard.compose("nihao")
        assertEquals("nihao", keyboard.composing.value)
        assertEquals(emptyList(), sent(session))

        keyboard.commit("你好")
        assertEquals("", keyboard.composing.value)
        assertEquals(listOf(text("你好")), sent(session))
    }

    @Test
    fun finishingWithoutChoosingCommitsTheComposingText() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.compose("hello")
        keyboard.finishComposing()
        keyboard.finishComposing()

        assertEquals(listOf(text("hello")), sent(session))
    }

    @Test
    fun newlinesBecomeEnterKeys() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.commit("第一行\n第二行\n")

        assertEquals(
            listOf(text("第一行"), key(KeyPress.Key.KEY_ENTER), text("第二行"), key(KeyPress.Key.KEY_ENTER)),
            sent(session),
        )
    }

    @Test
    fun deletingAroundTheInsertionPointBecomesBackspaceAndDelete() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.delete(before = 2, after = 1)

        assertEquals(
            listOf(key(KeyPress.Key.KEY_BACKSPACE), key(KeyPress.Key.KEY_BACKSPACE), key(KeyPress.Key.KEY_DELETE)),
            sent(session),
        )
    }

    @Test
    fun aLatchedModifierGoesWithTheNextKeyOnly() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.modifiers.tap(ModifierKey.MODIFIER_KEY_ALT, 300.milliseconds)
        keyboard.press(KeyPress.Key.KEY_LEFT)
        keyboard.press(KeyPress.Key.KEY_LEFT)

        assertEquals(
            listOf(
                ClientMessage(key_press = KeyPress(key = KeyPress.Key.KEY_LEFT, modifiers = listOf(ModifierKey.MODIFIER_KEY_ALT))),
                key(KeyPress.Key.KEY_LEFT),
            ),
            sent(session),
        )
    }

    @Test
    fun typedCharactersBecomeChordsWhileAModifierIsActive() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.modifiers.lock(ModifierKey.MODIFIER_KEY_META)
        keyboard.commit("wt")
        keyboard.modifiers.releaseAll()
        keyboard.commit("ok")

        val meta = listOf(ModifierKey.MODIFIER_KEY_META)
        assertEquals(
            listOf(
                ClientMessage(character_press = CharacterPress(character = "w", modifiers = meta)),
                ClientMessage(character_press = CharacterPress(character = "t", modifiers = meta)),
                text("ok"),
            ),
            sent(session),
        )
    }

    @Test
    fun onlyTheFirstCharacterTakesALatchedModifier() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.modifiers.tap(ModifierKey.MODIFIER_KEY_CONTROL, 300.milliseconds)
        keyboard.commit("cd")

        assertEquals(
            listOf(
                ClientMessage(character_press = CharacterPress(character = "c", modifiers = listOf(ModifierKey.MODIFIER_KEY_CONTROL))),
                text("d"),
            ),
            sent(session),
        )
    }

    @Test
    fun mediaKeysAndShortcutsLeaveModifiersAlone() = runTest {
        val (keyboard, session) = connectedKeyboard()

        keyboard.modifiers.tap(ModifierKey.MODIFIER_KEY_SHIFT, 300.milliseconds)
        keyboard.media(KeyPress.Key.KEY_PLAY_PAUSE)
        keyboard.shortcut(ShortcutAction.Shortcut.SHORTCUT_COPY)
        keyboard.press(KeyPress.Key.KEY_DOWN)

        assertEquals(
            listOf(
                key(KeyPress.Key.KEY_PLAY_PAUSE),
                ClientMessage(shortcut_action = ShortcutAction(shortcut = ShortcutAction.Shortcut.SHORTCUT_COPY)),
                ClientMessage(key_press = KeyPress(key = KeyPress.Key.KEY_DOWN, modifiers = listOf(ModifierKey.MODIFIER_KEY_SHIFT))),
            ),
            sent(session),
        )
    }
}
