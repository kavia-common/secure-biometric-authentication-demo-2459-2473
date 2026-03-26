package org.example.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageUtilsTest {
    @Test
    fun testGetMessage() {
        assertEquals("Hello     World!", MessageUtils.message())
    }
}
