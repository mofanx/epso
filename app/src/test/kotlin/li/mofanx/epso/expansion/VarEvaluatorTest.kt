package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VarEvaluator 单元测试
 *
 * 注意：clipboard 类型依赖 Android Context，跳过不测。
 * match 类型递归依赖 MatchStore（Android singleton），跳过不测。
 * 这里测 echo / date / random / choice / propagateCase / strftime 转换。
 */
class VarEvaluatorTest {

    private fun evaluator(globalVars: List<Var> = emptyList()) =
        VarEvaluator(globalVars)

    // ── echo ────────────────────────────────────────────────────────

    @Test
    fun `echo var is substituted`() = runTest {
        val ev = evaluator()
        val result = ev.evaluate(
            replace = "Hello, {{name}}!",
            localVars = listOf(echoVar("name", "World")),
        )
        assertEquals("Hello, World!", result)
    }

    @Test
    fun `multiple echo vars replaced`() = runTest {
        val ev = evaluator()
        val result = ev.evaluate(
            replace = "{{greeting}}, {{name}}!",
            localVars = listOf(echoVar("greeting", "Hi"), echoVar("name", "Alice")),
        )
        assertEquals("Hi, Alice!", result)
    }

    @Test
    fun `unknown var placeholder preserved`() = runTest {
        val ev = evaluator()
        val result = ev.evaluate(
            replace = "Hello, {{unknown}}!",
            localVars = emptyList(),
        )
        assertEquals("Hello, {{unknown}}!", result)
    }

    @Test
    fun `local var overrides global var`() = runTest {
        val globalName = echoVar("name", "Global")
        val ev = evaluator(globalVars = listOf(globalName))
        val result = ev.evaluate(
            replace = "Hello, {{name}}!",
            localVars = listOf(echoVar("name", "Local")),
        )
        assertEquals("Hello, Local!", result)
    }

    @Test
    fun `global var used when no local override`() = runTest {
        val ev = evaluator(globalVars = listOf(echoVar("sig", "John Doe")))
        val result = ev.evaluate(
            replace = "Regards, {{sig}}",
            localVars = emptyList(),
        )
        assertEquals("Regards, John Doe", result)
    }

    @Test
    fun `blank replace returns unchanged`() = runTest {
        val ev = evaluator()
        assertEquals("", ev.evaluate("", emptyList()))
        assertEquals("   ", ev.evaluate("   ", emptyList()))
    }

    @Test
    fun `no placeholder returns unchanged`() = runTest {
        val ev = evaluator()
        assertEquals("no vars here", ev.evaluate("no vars here", emptyList()))
    }

    // ── random / choice ─────────────────────────────────────────────

    @Test
    fun `random picks one of the choices`() = runTest {
        val choices = listOf("A", "B", "C")
        val ev = evaluator()
        val v = Var(
            name = "r",
            type = "random",
            params = VarParams(choices = choices),
        )
        repeat(20) {
            val result = ev.evaluate("{{r}}", localVars = listOf(v))
            assertTrue(result in choices, "Expected one of $choices but got '$result'")
        }
    }

    @Test
    fun `random with empty choices returns placeholder`() = runTest {
        val ev = evaluator()
        val v = Var(name = "r", type = "random", params = VarParams())
        val result = ev.evaluate("{{r}}", localVars = listOf(v))
        assertEquals("{{r}}", result)
    }

    @Test
    fun `choice picks one of the values`() = runTest {
        val values = listOf("x", "y")
        val ev = evaluator()
        val v = Var(
            name = "c",
            type = "choice",
            params = VarParams(values = values),
        )
        repeat(20) {
            val result = ev.evaluate("{{c}}", localVars = listOf(v))
            assertTrue(result in values)
        }
    }

    // ── date / strftime ─────────────────────────────────────────────

    @Test
    fun `date var produces current year`() = runTest {
        val ev = evaluator()
        val currentYear = LocalDateTime.now().year.toString()
        val v = Var(name = "d", type = "date", params = VarParams(format = "%Y"))
        val result = ev.evaluate("{{d}}", localVars = listOf(v))
        assertEquals(currentYear, result)
    }

    @Test
    fun `date var full format YYYY-MM-DD`() = runTest {
        val ev = evaluator()
        val expected = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val v = Var(name = "d", type = "date", params = VarParams(format = "%Y-%m-%d"))
        val result = ev.evaluate("{{d}}", localVars = listOf(v))
        assertEquals(expected, result)
    }

    @Test
    fun `date format with literal text`() = runTest {
        val ev = evaluator()
        val v = Var(name = "d", type = "date", params = VarParams(format = "Year:%Y"))
        val result = ev.evaluate("{{d}}", localVars = listOf(v))
        val year = LocalDateTime.now().year.toString()
        assertEquals("Year:$year", result)
    }

    @Test
    fun `date format literal with single quote is escaped`() = runTest {
        val ev = evaluator()
        // Format: "it's %Y" — the apostrophe must be properly escaped
        val v = Var(name = "d", type = "date", params = VarParams(format = "Y'%Y"))
        // Should not throw; just verify year is present
        val result = ev.evaluate("{{d}}", localVars = listOf(v))
        assertTrue(result.contains(LocalDateTime.now().year.toString()),
            "Expected year in result, got: $result")
    }

    // ── unsupported type ────────────────────────────────────────────

    @Test
    fun `unsupported var type leaves placeholder`() = runTest {
        val ev = evaluator()
        val v = Var(name = "s", type = "shell", params = VarParams(cmd = "echo hi"))
        val result = ev.evaluate("{{s}}", localVars = listOf(v))
        assertEquals("{{s}}", result)
    }

    // ── propagateCase ───────────────────────────────────────────────

    @Test
    fun `propagateCase all-upper → uppercase replace`() {
        assertEquals("HELLO WORLD", applyPropagateCase("TRIGGER", "hello world", "uppercase"))
    }

    @Test
    fun `propagateCase all-upper + capitalize style`() {
        assertEquals("Hello World", applyPropagateCase("TRIGGER", "hello world", "capitalize_words"))
    }

    @Test
    fun `propagateCase capitalized trigger → capitalize replace`() {
        assertEquals("Hello world", applyPropagateCase("Trigger", "hello world", "uppercase"))
    }

    @Test
    fun `propagateCase lowercase trigger → no change`() {
        assertEquals("hello world", applyPropagateCase("trigger", "hello world", "uppercase"))
    }

    @Test
    fun `propagateCase empty replace unchanged`() {
        assertEquals("", applyPropagateCase("TRIGGER", "", "uppercase"))
    }

    @Test
    fun `propagateCase trigger with no letters unchanged`() {
        assertEquals("hello", applyPropagateCase("::", "hello", "uppercase"))
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun echoVar(name: String, value: String) = Var(
        name = name,
        type = "echo",
        params = VarParams(echo = value),
    )
}
