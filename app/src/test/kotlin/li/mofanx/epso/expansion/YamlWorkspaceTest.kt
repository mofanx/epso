package li.mofanx.epso.expansion

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamlWorkspaceTest {

    private lateinit var tmpDir: File
    private lateinit var ws: YamlWorkspace

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("epso-ws-test").toFile()
        ws = YamlWorkspace(tmpDir)
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── createFile / listFiles ───────────────────────────────────────

    @Test
    fun `createFile creates yml in workspace dir`() = runTest {
        val f = ws.createFile("email")
        assertTrue(f.exists())
        assertEquals("email.yml", f.name)
        assertTrue(f.absolutePath.startsWith(tmpDir.absolutePath))
    }

    @Test
    fun `createFile sanitizes name`() = runTest {
        val f = ws.createFile("my file/with:special chars!")
        assertTrue(f.name.matches(Regex("[a-zA-Z0-9_\\-]+\\.yml")))
    }

    @Test
    fun `createFile is idempotent`() = runTest {
        val f1 = ws.createFile("test")
        val f2 = ws.createFile("test")
        assertEquals(f1.absolutePath, f2.absolutePath)
        assertEquals(1, ws.listFiles().size)
    }

    @Test
    fun `listFiles returns all yml files recursively`() = runTest {
        ws.createFile("a")
        ws.createFile("b")
        // create a non-yaml file — should not be listed by listFiles()
        tmpDir.resolve("readme.txt").writeText("hi")
        // create a subdir yaml — listFiles() is recursive, so it should be included
        val sub = tmpDir.resolve("subdir").also { it.mkdirs() }
        sub.resolve("package.yml").writeText("")
        assertEquals(3, ws.listFiles().size)
    }

    // ── readFile / writeFile ─────────────────────────────────────────

    @Test
    fun `writeFile and readFile round-trips matches`() = runTest {
        val file = ws.createFile("base")
        val group = MatchGroup(
            matches = listOf(
                Match(trigger = ":hello", replace = "Hello World"),
                Match(trigger = ":eml", replace = "test@example.com"),
            )
        )
        ws.writeFile(file, group)
        val loaded = ws.readFile(file)
        assertEquals(2, loaded.matches.size)
        assertEquals(":hello", loaded.matches[0].trigger)
        assertEquals("test@example.com", loaded.matches[1].replace)
    }

    @Test
    fun `writeFile is atomic - file not corrupted on read`() = runTest {
        val file = ws.createFile("base")
        val original = MatchGroup(matches = listOf(Match(trigger = ":a", replace = "A")))
        ws.writeFile(file, original)
        // Overwrite with new content
        val updated = MatchGroup(matches = listOf(Match(trigger = ":b", replace = "B")))
        ws.writeFile(file, updated)
        val loaded = ws.readFile(file)
        assertEquals(1, loaded.matches.size)
        assertEquals(":b", loaded.matches[0].trigger)
    }

    @Test
    fun `readFile on empty yaml returns empty MatchGroup`() = runTest {
        val file = tmpDir.resolve("empty.yml").also { it.writeText("") }
        val group = ws.readFile(file)
        assertTrue(group.matches.isEmpty())
        assertTrue(group.globalVars.isEmpty())
    }

    @Test
    fun `readFile on invalid yaml returns empty MatchGroup without throwing`() = runTest {
        val file = tmpDir.resolve("bad.yml").also { it.writeText("{{{{not yaml}}}}") }
        val group = ws.readFile(file)
        assertTrue(group.matches.isEmpty())
    }

    // ── loadAll ──────────────────────────────────────────────────────

    @Test
    fun `loadAll merges matches from multiple files`() = runTest {
        val f1 = ws.createFile("a")
        val f2 = ws.createFile("b")
        ws.writeFile(f1, MatchGroup(matches = listOf(Match(trigger = ":a", replace = "A"))))
        ws.writeFile(f2, MatchGroup(matches = listOf(Match(trigger = ":b", replace = "B"))))
        val (dict, _) = ws.loadAll()
        assertEquals(2, dict.size)
        assertTrue(":a" in dict)
        assertTrue(":b" in dict)
    }

    @Test
    fun `loadAll later file overrides earlier trigger`() = runTest {
        // Files are loaded sorted by name: a.yml then b.yml
        val f1 = ws.createFile("a")
        val f2 = ws.createFile("b")
        ws.writeFile(f1, MatchGroup(matches = listOf(Match(trigger = ":dup", replace = "from-a"))))
        ws.writeFile(f2, MatchGroup(matches = listOf(Match(trigger = ":dup", replace = "from-b"))))
        val (dict, _) = ws.loadAll()
        assertEquals("from-b", dict[":dup"]?.replace)
    }

    @Test
    fun `loadAll resolves imports`() = runTest {
        val pkg = tmpDir.resolve("packages/lorem").also { it.mkdirs() }
        pkg.resolve("package.yml").writeText(
            "matches:\n  - trigger: \":lorem\"\n    replace: \"Lorem ipsum\"\n"
        )
        val base = ws.createFile("base")
        ws.writeFile(base, MatchGroup(imports = listOf("packages/lorem/package.yml")))
        val (dict, _) = ws.loadAll()
        assertTrue(":lorem" in dict, "Expected :lorem from imported package, got: ${dict.keys}")
    }

    @Test
    fun `loadAll handles circular imports without infinite loop`() = runTest {
        val f1 = tmpDir.resolve("a.yml")
        val f2 = tmpDir.resolve("b.yml")
        f1.writeText("imports:\n  - b.yml\nmatches:\n  - trigger: \":a\"\n    replace: \"A\"\n")
        f2.writeText("imports:\n  - a.yml\nmatches:\n  - trigger: \":b\"\n    replace: \"B\"\n")
        // Should terminate without StackOverflow
        val (dict, _) = ws.loadAll()
        assertTrue(dict.isNotEmpty())
    }

    @Test
    fun `loadAll collects global vars`() = runTest {
        val f = ws.createFile("base")
        ws.writeFile(f, MatchGroup(
            globalVars = listOf(Var(name = "myname", type = "echo", params = VarParams(echo = "Alice"))),
        ))
        val (_, vars) = ws.loadAll()
        assertEquals(1, vars.size)
        assertEquals("myname", vars[0].name)
        assertEquals("Alice", vars[0].params.echo)
    }

    // ── deleteFile ───────────────────────────────────────────────────

    @Test
    fun `deleteFile removes file`() = runTest {
        val f = ws.createFile("todelete")
        assertTrue(f.exists())
        ws.deleteFile(f)
        assertFalse(f.exists())
        assertEquals(0, ws.listFiles().size)
    }

    @Test
    fun `deleteFile on non-existent file does not throw`() {
        val ghost = tmpDir.resolve("ghost.yml")
        ws.deleteFile(ghost)  // should not throw
    }

    // ── parseYaml / encodeGroup ──────────────────────────────────────

    @Test
    fun `parseYaml handles espanso-style yaml`() {
        val yaml = """
            global_vars:
              - name: myname
                type: echo
                params:
                  echo: "John"
            matches:
              - trigger: ":hello"
                replace: "Hello, {{myname}}!"
              - triggers:
                  - ":bye"
                  - ":goodbye"
                replace: "Goodbye!"
        """.trimIndent()
        val group = ws.parseYaml(yaml)
        assertEquals(1, group.globalVars.size)
        assertEquals(2, group.matches.size)
        assertEquals(listOf(":bye", ":goodbye"), group.matches[1].triggers)
    }

    @Test
    fun `encodeGroup and parseYaml are inverse`() {
        val original = MatchGroup(
            matches = listOf(
                Match(trigger = ":test", replace = "Test value", word = true),
            ),
            globalVars = listOf(Var(name = "x", type = "echo", params = VarParams(echo = "1"))),
        )
        val yaml = ws.encodeGroup(original)
        val restored = ws.parseYaml(yaml)
        assertEquals(original.matches.size, restored.matches.size)
        assertEquals(original.matches[0].trigger, restored.matches[0].trigger)
        assertEquals(original.matches[0].word, restored.matches[0].word)
        assertEquals(original.globalVars[0].name, restored.globalVars[0].name)
    }

    // ── copy / move non-YAML files ───────────────────────────────────

    @Test
    fun `copyFile copies non-yaml file as-is`() = runTest {
        val script = tmpDir.resolve("script.js").also { it.writeText("console.log('hi')") }
        val copied = ws.copyFile(script, "scripts", "")
        assertTrue(copied.exists())
        assertEquals("script.js", copied.name)
        assertEquals("console.log('hi')", copied.readText())
        assertEquals("scripts/script.js", copied.relativeTo(tmpDir).path.replace(File.separator, "/"))
    }

    @Test
    fun `moveFile moves non-yaml file as-is`() = runTest {
        val script = tmpDir.resolve("script.js").also { it.writeText("console.log('hi')") }
        val moved = ws.moveFile(script, "scripts", "")
        assertTrue(moved.exists())
        assertFalse(script.exists())
        assertEquals("console.log('hi')", moved.readText())
    }

    // ── anchors/aliases ─────────────────────────────────────────────

    @Test
    fun `parseYaml resolves YAML anchors and aliases`() {
        val yaml = """
            anchors:
              - &script1 |
                fruits = ["apple", "banana"]
            matches:
              - trigger: ":test"
                replace: "{{output}}"
                vars:
                  - name: output
                    type: script
                    params:
                      args: [python, -c, *script1]
        """.trimIndent()
        val group = ws.parseYaml(yaml)
        assertEquals(1, group.matches.size)
        assertEquals(
            listOf("python", "-c", "fruits = [\"apple\", \"banana\"]\n"),
            group.matches[0].vars[0].params.args,
        )
    }
}
