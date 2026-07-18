package li.mofanx.epso.expansion

import org.junit.Test
import kotlin.test.assertEquals

class VarEnvUtilsTest {

    @Test
    fun `string variable becomes ESPANSO_NAME`() {
        val env = envFromValues(mapOf("name" to "John"))
        assertEquals("John", env["ESPANSO_NAME"])
    }

    @Test
    fun `form map flattens into ESPANSO_FORMNAME_FIELD`() {
        val env = envFromValues(mapOf("form1" to mapOf("name" to "John", "age" to "30")))
        assertEquals("John", env["ESPANSO_FORM1_NAME"])
        assertEquals("30", env["ESPANSO_FORM1_AGE"])
    }

    @Test
    fun `null values are skipped`() {
        val env = envFromValues(mapOf("name" to null, "age" to "30"))
        assertEquals(null, env["ESPANSO_NAME"])
        assertEquals("30", env["ESPANSO_AGE"])
    }

    @Test
    fun `non-string values use toString`() {
        val env = envFromValues(mapOf("num" to 42))
        assertEquals("42", env["ESPANSO_NUM"])
    }
}
