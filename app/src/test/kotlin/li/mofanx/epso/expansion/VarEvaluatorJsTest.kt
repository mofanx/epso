package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VarEvaluatorJsTest {

    @Test
    fun `javascript variable is evaluated and replaced`() = runTest {
        val evaluator = VarEvaluator(globalVars = emptyList())
        val localVars = listOf(
            Var(
                name = "jsvar",
                type = "javascript",
                params = VarParams(code = "'Hello from JS'")
            )
        )
        val result = evaluator.evaluate("Result: {{jsvar}}", localVars)
        assertEquals("Result: Hello from JS", result)
    }

    @Test
    fun `javascript variable can use injected values`() = runTest {
        val evaluator = VarEvaluator(globalVars = emptyList())
        val localVars = listOf(
            Var(name = "name", type = "echo", params = VarParams(echo = "epso")),
            Var(
                name = "greeting",
                type = "javascript",
                params = VarParams(code = "'Hi, ' + name"),
                dependsOn = listOf("name"),
            )
        )
        val result = evaluator.evaluate("{{greeting}}", localVars)
        assertEquals("Hi, epso", result)
    }

    @Test
    fun `javascript error with ignore_error returns empty`() = runTest {
        val evaluator = VarEvaluator(globalVars = emptyList())
        val localVars = listOf(
            Var(
                name = "bad",
                type = "javascript",
                params = VarParams(code = "throw 1", ignoreError = true)
            )
        )
        val result = evaluator.evaluate("A{{bad}}B", localVars)
        assertEquals("AB", result)
    }

    @Test
    fun `javascript error without ignore_error keeps placeholder`() = runTest {
        val evaluator = VarEvaluator(globalVars = emptyList())
        val localVars = listOf(
            Var(
                name = "bad",
                type = "javascript",
                params = VarParams(code = "throw 1")
            )
        )
        val result = evaluator.evaluate("A{{bad}}B", localVars)
        assertEquals("A{{bad}}B", result)
    }
}
