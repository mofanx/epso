package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaScriptVarRunnerTest {

    @Test
    fun `javascript returns last expression as string`() = runTest {
        val result = JavaScriptVarRunner.run(code = "2 + 3")
        assertEquals("5", result)
    }

    @Test
    fun `javascript can access injected variables`() = runTest {
        val result = JavaScriptVarRunner.run(
            code = "'Hello, ' + name",
            values = mapOf("name" to "epso"),
        )
        assertEquals("Hello, epso", result)
    }

    @Test
    fun `javascript error returns null by default`() = runTest {
        val result = JavaScriptVarRunner.run(code = "throw new Error('boom')")
        assertNull(result)
    }

    @Test
    fun `javascript error with ignore_error returns empty string`() = runTest {
        val result = JavaScriptVarRunner.run(
            code = "throw new Error('boom')",
            ignoreError = true,
        )
        assertEquals("", result)
    }

    @Test
    fun `javascript trim is applied`() = runTest {
        val result = JavaScriptVarRunner.run(
            code = "'  padded  '",
            trim = true,
        )
        assertEquals("padded", result)
    }

    @Test
    fun `javascript no trim keeps whitespace`() = runTest {
        val result = JavaScriptVarRunner.run(
            code = "'  padded  '",
            trim = false,
        )
        assertEquals("  padded  ", result)
    }
}
