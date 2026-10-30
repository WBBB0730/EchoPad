package com.wbbb.echopad.mobile.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    @Test
    fun parsesTheDesktopPairingLink() {
        assertEquals(
            PairingLink("192.168.77.149", 47321, "Ab-c_9", "desk-1", "fp_9-x"),
            PairingLink.parse("echopad://pair?host=192.168.77.149&port=47321&token=Ab-c_9&id=desk-1&fp=fp_9-x"),
        )
    }

    @Test
    fun rejectsOtherCodes() {
        assertNull(PairingLink.parse("https://example.com/pair?host=1.2.3.4&port=1&token=a&id=d&fp=f"))
        assertNull(PairingLink.parse("echopad://pair?host=1.2.3.4&port=1&id=d&fp=f"))
        assertNull(PairingLink.parse("echopad://pair?host=1.2.3.4&port=99999&token=a&id=d&fp=f"))
        assertNull(PairingLink.parse("随便一段文字"))
    }

    @Test
    fun rejectsCodesFromVersionsWithoutACertificate() {
        assertNull(PairingLink.parse("echopad://pair?host=1.2.3.4&port=47321&token=a"))
        assertNull(PairingLink.parse("echopad://pair?host=1.2.3.4&port=47321&token=a&id=d"))
    }
}
