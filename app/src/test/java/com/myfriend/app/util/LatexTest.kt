package com.myfriend.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexTest {
    @Test
    fun convertsCommonSymbols() {
        val text = Latex.toReadable("\\alpha \\times \\beta \\le \\infty")
        assertTrue(text.contains("α"))
        assertTrue(text.contains("×"))
        assertTrue(text.contains("β"))
        assertTrue(text.contains("≤"))
        assertTrue(text.contains("∞"))
    }

    @Test
    fun convertsFractionsAndRoots() {
        assertEquals("(a + b)⁄c", Latex.toReadable("\\frac{a + b}{c}"))
        assertTrue(Latex.toReadable("\\sqrt{x^2 + y^2}").startsWith("√"))
    }

    @Test
    fun convertsSuperscripts() {
        assertEquals("x² + y²", Latex.toReadable("x^{2} + y^{2}"))
    }

    @Test
    fun convertsBarePowers() {
        assertEquals("E = mc²", Latex.toReadable("E = mc^2"))
    }
}
