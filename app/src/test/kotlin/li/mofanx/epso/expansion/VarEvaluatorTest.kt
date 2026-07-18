package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VarEvaluator 单元测试
 *
 * 注意：clipboard 类型依赖 Android Context，跳过不测。
 * match 类型递归依赖 MatchStore（Android singleton），跳过不测。
 * 这里测 echo / date / random / choice / propagateCase / strftime 转换。
 */
class VarEvaluatorTest {

    private fun evaluator(
        globalVars: List<Var> = emptyList(),
        formLauncher: suspend (Var) -> Map<String, String>? = { null },
        choiceLauncher: suspend (Var) -> String? = { null },
    ) = VarEvaluator(globalVars, formLauncher = formLauncher, choiceLauncher = choiceLauncher)

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
    fun `random with empty choices falls back to min-max range`() = runTest {
        val ev = evaluator()
        // No choices → uses min/max (default 0–100)
        val v = Var(name = "r", type = "random", params = VarParams())
        val result = ev.evaluate("{{r}}", localVars = listOf(v))
        val num = result.toIntOrNull()
        assertNotNull(num, "Expected a number from min/max fallback, got: $result")
        assertTrue(num in 0..100)
    }

    @Test
    fun `random with explicit min-max returns number in range`() = runTest {
        val ev = evaluator()
        val v = Var(name = "r", type = "random", params = VarParams(min = 5, max = 10))
        repeat(30) {
            val result = ev.evaluate("{{r}}", localVars = listOf(v))
            val num = result.toIntOrNull()
            assertNotNull(num)
            assertTrue(num in 5..10)
        }
    }

    @Test
    fun `choice picks one of the values`() = runTest {
        val values = listOf("x", "y")
        val ev = evaluator(choiceLauncher = { values.first() })
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

    // ── depends_on 拓扑排序 ─────────────────────────────────────────

    @Test
    fun `depends_on is evaluated in correct order`() = runTest {
        val second = echoVar("second", "World")
        val first = Var(
            name = "first",
            type = "echo",
            params = VarParams(echo = "Hello"),
            dependsOn = listOf("second"),
        )
        val ev = evaluator()
        val result = ev.evaluate(
            replace = "{{first}} {{second}}",
            localVars = listOf(first, second),
        )
        assertEquals("Hello World", result)
    }

    @Test
    fun `circular depends_on does not crash and remaining vars evaluate`() = runTest {
        val a = Var(name = "a", type = "echo", params = VarParams(echo = "A"), dependsOn = listOf("b"))
        val b = Var(name = "b", type = "echo", params = VarParams(echo = "B"), dependsOn = listOf("a"))
        val ev = evaluator()
        // should not throw
        val result = ev.evaluate("{{a}}", localVars = listOf(a, b))
        assertTrue(result == "A" || result == "{{a}}")
    }

    // ── unsupported type ────────────────────────────────────────────

    @Test
    fun `unsupported var type leaves placeholder`() = runTest {
        val ev = evaluator()
        val v = Var(name = "s", type = "unsupported", params = VarParams(cmd = "echo hi"))
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

    // ── form / choice 交互变量 ───────────────────────────────────────

    @Test
    fun `form var uses launcher result fields`() = runTest {
        val ev = evaluator(formLauncher = { mapOf("name" to "Alice", "age" to "30") })
        val v = Var(name = "f", type = "form", params = VarParams())
        val result = ev.evaluate("Name: {{f.name}}, Age: {{f.age}}", localVars = listOf(v))
        assertEquals("Name: Alice, Age: 30", result)
    }

    @Test
    fun `form var with null launcher throws FormCanceledException`() = runTest {
        val ev = evaluator()
        val v = Var(name = "f", type = "form", params = VarParams())
        assertFailsWith<FormCanceledException> {
            ev.evaluate("{{f}}", localVars = listOf(v))
        }
    }

    @Test
    fun `choice var with null launcher throws FormCanceledException`() = runTest {
        val ev = evaluator()
        val v = Var(name = "c", type = "choice", params = VarParams(values = listOf("A", "B")))
        assertFailsWith<FormCanceledException> {
            ev.evaluate("{{c}}", localVars = listOf(v))
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun echoVar(name: String, value: String) = Var(
        name = name,
        type = "echo",
        params = VarParams(echo = value),
    )
}
