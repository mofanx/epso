package li.mofanx.epso.expansion

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchFilterTest {

    private fun matchWithFilters(
        filterExec: String? = null,
        filterTitle: String? = null,
        filterClass: String? = null,
        filterOs: String? = null,
        enable: Boolean? = null,
    ): Match {
        return Match(
            trigger = ":t",
            replace = "x",
            filterExec = filterExec,
            filterTitle = filterTitle,
            filterClass = filterClass,
            filterOs = filterOs,
            enable = enable,
        ).apply {
            effectiveFilterExec = filterExec
            effectiveFilterTitle = filterTitle
            effectiveFilterClass = filterClass
            effectiveFilterOs = filterOs
            effectiveEnable = enable ?: true
        }
    }

    @Test
    fun `filter_exec matches package name`() {
        val m = matchWithFilters(filterExec = "tencent")
        assertTrue(m.isActiveFor(packageName = "com.tencent.mm"))
        assertFalse(m.isActiveFor(packageName = "com.google.android.apps.messaging"))
    }

    @Test
    fun `filter_exec supports regex`() {
        val m = matchWithFilters(filterExec = "^com\\.tencent\\..*$")
        assertTrue(m.isActiveFor(packageName = "com.tencent.mm"))
        assertFalse(m.isActiveFor(packageName = "com.google.android"))
    }

    @Test
    fun `multiple filters all must match`() {
        val m = matchWithFilters(filterExec = "tencent", filterTitle = "Chat")
        assertTrue(m.isActiveFor(packageName = "com.tencent.mm", title = "Chat"))
        assertFalse(m.isActiveFor(packageName = "com.tencent.mm", title = "Settings"))
        assertFalse(m.isActiveFor(packageName = "com.google.android", title = "Chat"))
    }

    @Test
    fun `filter set but property missing fails`() {
        val m = matchWithFilters(filterTitle = "Chat")
        assertFalse(m.isActiveFor(packageName = "com.tencent.mm"))
    }

    @Test
    fun `enable false disables match`() {
        val m = matchWithFilters(enable = false)
        assertFalse(m.isActiveFor(packageName = "com.tencent.mm"))
    }

    @Test
    fun `no filters means active`() {
        val m = matchWithFilters()
        assertTrue(m.isActiveFor(packageName = "anything"))
    }

    @Test
    fun `invalid regex falls back to substring`() {
        val m = matchWithFilters(filterExec = "[invalid")
        assertTrue(m.isActiveFor(packageName = "[invalid here"))
    }
}
