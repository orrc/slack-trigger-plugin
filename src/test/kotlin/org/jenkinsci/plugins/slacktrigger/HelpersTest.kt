package org.jenkinsci.plugins.slacktrigger

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpersTest {

    @Test
    fun `empty string should given an empty result`() {
        parseQueryStringAndAssert("", emptyMap())
    }

    @Test
    fun `values can be empty`() {
        parseQueryStringAndAssert("a=", mapOf("a" to ""))
    }

    @Test
    fun `a single key-value pair should work`() {
        parseQueryStringAndAssert("a=b", mapOf("a" to "b"))
    }

    @Test
    fun `multiple key-value pairs should work`() {
        parseQueryStringAndAssert("a=b&c=d&e=f", mapOf("a" to "b", "c" to "d", "e" to "f"))
    }

    @Test
    fun `values should be URL-decoded`() {
        parseQueryStringAndAssert(
            "dog=woof+woof&cat=%F0%9F%90%B1&",
            mapOf("dog" to "woof woof", "cat" to "🐱")
        )
    }

    private fun parseQueryStringAndAssert(input: String, expectedOutput: Map<String, String>) {
        val actualOutput = parseQueryString(input)
        assertEquals(expectedOutput, actualOutput)
    }

}
