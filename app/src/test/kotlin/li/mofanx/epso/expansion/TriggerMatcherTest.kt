package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TriggerMatcherTest {

    private lateinit var matcher: TriggerMatcher

    @Before
    fun setUp() {
        matcher = TriggerMatcher()
    }

    // ── 精确匹配 ────────────────────────────────────────────────────

    @Test
    fun `exact match found at end of text`() = runTest {
        matcher.addMatch(match(":eml", "test@example.com"))
        val r = matcher.match("please send to :eml")
        assertNotNull(r)
        assertEquals(":eml", r.matchedText)
        assertEquals("test@example.com", r.match.replace)
    }

    @Test
    fun `exact match not found when trigger absent`() = runTest {
        matcher.addMatch(match(":eml", "test@example.com"))
        assertNull(matcher.match("hello world"))
    }

    @Test
    fun `longer trigger wins over shorter prefix`() = runTest {
        matcher.addMatch(match(":p", "short"))
        matcher.addMatch(match(":phone", "123-456-7890"))
        // ":phone" ends the text — longer match wins
        val r = matcher.match("call :phone")
        assertNotNull(r)
        assertEquals(":phone", r.matchedText)
    }

    @Test
    fun `multiple triggers on same match`() = runTest {
        val m = Match(
            trigger = ":hello",
            triggers = listOf(":hi", ":hey"),
            replace = "Hello there!",
        )
        matcher.addMatch(m)
        assertNotNull(matcher.match("say :hi"))
        assertNotNull(matcher.match("say :hey"))
        assertNotNull(matcher.match("say :hello"))
    }

    @Test
    fun `match returns correct start and end indices`() = runTest {
        matcher.addMatch(match(":sig", "John Doe"))
        val text = "regards, :sig"
        val r = matcher.match(text)
        assertNotNull(r)
        assertEquals(9, r.startIndex)
        assertEquals(13, r.endIndex)
        assertEquals(":sig", text.substring(r.startIndex, r.endIndex))
    }

    // ── 单词边界 ────────────────────────────────────────────────────

    @Test
    fun `wordTrue match when surrounded by separators`() = runTest {
        matcher.addMatch(Match(trigger = "hello", replace = "hi", word = true))
        assertNotNull(matcher.match("say hello world"))
    }

    @Test
    fun `wordTrue no match when no left separator`() = runTest {
        matcher.addMatch(Match(trigger = "hello", replace = "hi", word = true))
        // "xhello" — left side is 'x', not a separator
        assertNull(matcher.match("xhello world"))
    }

    @Test
    fun `wordTrue no match when no right separator`() = runTest {
        matcher.addMatch(Match(trigger = "hello", replace = "hi", word = true))
        // "hellox" — right side is 'x'
        assertNull(matcher.match("say hellox"))
    }

    @Test
    fun `wordTrue match at start of string`() = runTest {
        matcher.addMatch(Match(trigger = "hello", replace = "hi", word = true))
        assertNotNull(matcher.match("hello world"))
    }

    @Test
    fun `wordTrue match at end of string`() = runTest {
        matcher.addMatch(Match(trigger = "hello", replace = "hi", word = true))
        assertNotNull(matcher.match("say hello"))
    }

    @Test
    fun `leftWordOnly match when left is separator`() = runTest {
        matcher.addMatch(Match(trigger = "fn", replace = "function", leftWord = true))
        assertNotNull(matcher.match(" fn("))   // left=' ', right='(' — only left checked
    }

    @Test
    fun `rightWordOnly match when right is separator`() = runTest {
        matcher.addMatch(Match(trigger = "fn", replace = "function", rightWord = true))
        assertNotNull(matcher.match("xfn("))   // left='x' ok (not checked), right='(' ok
        assertNull(matcher.match("xfna"))      // right='a' not a separator
    }

    @Test
    fun `noWordBoundary match anywhere in string`() = runTest {
        matcher.addMatch(Match(trigger = "ab", replace = "AB"))
        assertNotNull(matcher.match("xabx"))   // no boundary restriction
    }

    @Test
    fun `custom word separators override default`() = runTest {
        val m = Match(trigger = "hello", replace = "hi", word = true)
            .apply { effectiveWordSeparators = setOf('#') }
        matcher.addMatch(m)
        assertNotNull(matcher.match("hello#world"))  // '#' is separator
        assertNull(matcher.match("hello.world"))     // '.' is not separator
    }

    // ── 正则匹配 ────────────────────────────────────────────────────

    @Test
    fun `regex match basic`() = runTest {
        matcher.addMatch(Match(regex = "\\d{4}-\\d{2}-\\d{2}", replace = "[date]"))
        val r = matcher.match("today is 2024-01-15 done")
        assertNotNull(r)
        assertEquals("2024-01-15", r.matchedText)
    }

    @Test
    fun `regex no match`() = runTest {
        matcher.addMatch(Match(regex = "\\d{4}-\\d{2}-\\d{2}", replace = "[date]"))
        assertNull(matcher.match("no date here"))
    }

    @Test
    fun `exact match takes precedence over regex`() = runTest {
        // Both could match "abc", exact should win
        matcher.addMatch(Match(trigger = "abc", replace = "EXACT"))
        matcher.addMatch(Match(regex = "a.c", replace = "REGEX"))
        val r = matcher.match("abc")
        assertNotNull(r)
        assertEquals("EXACT", r.match.replace)
    }

    @Test
    fun `invalid regex is silently ignored`() = runTest {
        matcher.addMatch(Match(regex = "[invalid(regex", replace = "bad"))
        assertNull(matcher.match("anything"))
        assertEquals(0, matcher.getMatchCount())
    }

    @Test
    fun `match with both trigger and regex uses regex`() = runTest {
        // 当 regex 非空时，该 Match 应走正则匹配通道
        matcher.addMatch(Match(trigger = "abc", regex = ":date [^ ]+", replace = "[date]"))
        val r = matcher.match("meet :date 2026-07-18 today")
        assertNotNull(r)
        assertEquals(":date 2026-07-18", r.matchedText)
        assertEquals("[date]", r.match.replace)
    }

    @Test
    fun `clear removes all matches`() = runTest {
        matcher.addMatch(match(":eml", "x"))
        matcher.addMatch(Match(regex = "\\d+", replace = "num"))
        matcher.clear()
        assertEquals(0, matcher.getMatchCount())
        assertNull(matcher.match(":eml"))
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun match(trigger: String, replace: String) =
        Match(trigger = trigger, replace = replace)
}
