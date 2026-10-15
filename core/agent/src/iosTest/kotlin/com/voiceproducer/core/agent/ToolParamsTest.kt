package com.voiceproducer.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolParamsTest {
    @Test
    fun `ToolParams parses numbers lists and nulls`() {
        val p =
            parseToolParams(
                """{"prompt":"x","length_s":90,"ratio":0.5,"instrumental":true,"add_styles":["a","b"],"n":null,"o":{"k":1},"s":"true"}""",
            )
        assertEquals("x", p["prompt"])
        val length = p["length_s"]
        assertTrue(length is Long, "length_s is ${length?.let { it::class }}")
        assertEquals(90L, length)
        val ratio = p["ratio"]
        assertTrue(ratio is Double, "ratio is ${ratio?.let { it::class }}")
        assertEquals(0.5, ratio)
        assertEquals(true, p["instrumental"])
        assertEquals(listOf("a", "b"), p["add_styles"])
        assertTrue(p.containsKey("n"))
        assertNull(p["n"])
        assertEquals(mapOf("k" to 1L), p["o"])
        assertEquals("true", p["s"])
    }

    @Test
    fun `empty or invalid json gives empty map`() {
        assertEquals(emptyMap<String, Any?>(), parseToolParams(""))
        assertEquals(emptyMap<String, Any?>(), parseToolParams("[1]"))
        assertEquals(emptyMap<String, Any?>(), parseToolParams("{not json"))
    }
}
