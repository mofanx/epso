package li.mofanx.epso.expansion.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private fun entry(
        path: String,
        lastModified: Long = 0L,
        size: Long = 0L,
        sha256: String = "",
        isDirectory: Boolean = false,
    ) = SyncFileEntry(path, lastModified, size, sha256, isDirectory)

    @Test
    fun `新增文件应推送到远程`() {
        val local = entry("base.yml", 100, 10, "abc")
        val action = resolveAction(
            path = "base.yml",
            localEntry = local,
            remoteEntry = null,
            localChange = ChangeType.ADDED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.PUSH,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.CopyToRemote)
    }

    @Test
    fun `本地删除应传播到远程`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = null,
            remoteEntry = entry("base.yml", 100, 10, "abc"),
            localChange = ChangeType.DELETED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.PUSH,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.DeleteRemote)
    }

    @Test
    fun `拉取方向不删除本地`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = null,
            remoteEntry = entry("base.yml", 100, 10, "abc"),
            localChange = ChangeType.DELETED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.PULL,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertNull(action)
    }

    @Test
    fun `双向同步中本地修改远程删除应恢复远程`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = entry("base.yml", 200, 10, "new"),
            remoteEntry = null,
            localChange = ChangeType.MODIFIED,
            remoteChange = ChangeType.DELETED,
            direction = Direction.SYNC,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(Side.LOCAL, action.winner)
    }

    @Test
    fun `冲突时时间较新的一方获胜`() {
        val local = entry("base.yml", 200, 10, "new")
        val remote = entry("base.yml", 100, 10, "old")
        val action = resolveAction(
            path = "base.yml",
            localEntry = local,
            remoteEntry = remote,
            localChange = ChangeType.MODIFIED,
            remoteChange = ChangeType.MODIFIED,
            direction = Direction.SYNC,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(Side.LOCAL, action.winner)
        assertEquals(Side.REMOTE, action.target)
    }

    @Test
    fun `KeepBoth 在冲突时生成 ResolveConflict 动作`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = entry("base.yml", 200, 10, "new"),
            remoteEntry = entry("base.yml", 100, 10, "old"),
            localChange = ChangeType.MODIFIED,
            remoteChange = ChangeType.MODIFIED,
            direction = Direction.SYNC,
            strategy = ConflictStrategy.KeepBoth,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(ConflictStrategy.KeepBoth, action.strategy)
    }

    @Test
    fun `computeChange 通过 sha256 判断内容变化`() {
        val old = entry("base.yml", 100, 10, "abc")
        val new = entry("base.yml", 200, 10, "abc")
        assertEquals(ChangeType.UNCHANGED, computeChange(new, old))
    }

    @Test
    fun `computeChange 检测到 sha256 不同为修改`() {
        val old = entry("base.yml", 100, 10, "abc")
        val new = entry("base.yml", 100, 10, "def")
        assertEquals(ChangeType.MODIFIED, computeChange(new, old))
    }

    @Test
    fun `computeChange 回退到 size 和 mtime 判断`() {
        val old = entry("base.yml", 100, 10, "")
        val new = entry("base.yml", 100, 10, "")
        assertEquals(ChangeType.UNCHANGED, computeChange(new, old))

        val changed = entry("base.yml", 100, 11, "")
        assertEquals(ChangeType.MODIFIED, computeChange(changed, old))
    }

    @Test
    fun `同一路径本地文件远端目录属于冲突`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = entry("base.yml", 200, 10, "new"),
            remoteEntry = entry("base.yml", 100, 0, "", isDirectory = true),
            localChange = ChangeType.UNCHANGED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.PUSH,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(Side.LOCAL, action.winner)
        assertEquals(Side.REMOTE, action.target)
    }

    @Test
    fun `同一路径本地目录远端文件冲突按方向解决`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = entry("base.yml", 100, 0, "", isDirectory = true),
            remoteEntry = entry("base.yml", 200, 10, "old"),
            localChange = ChangeType.UNCHANGED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.PULL,
            strategy = ConflictStrategy.LastWriteWins,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(Side.REMOTE, action.winner)
        assertEquals(Side.LOCAL, action.target)
    }

    @Test
    fun `类型冲突时 KeepBoth 仍生成 ResolveConflict`() {
        val action = resolveAction(
            path = "base.yml",
            localEntry = entry("base.yml", 200, 10, "new"),
            remoteEntry = entry("base.yml", 100, 0, "", isDirectory = true),
            localChange = ChangeType.UNCHANGED,
            remoteChange = ChangeType.UNCHANGED,
            direction = Direction.SYNC,
            strategy = ConflictStrategy.KeepBoth,
        )
        assertTrue(action is SyncAction.ResolveConflict)
        assertEquals(ConflictStrategy.KeepBoth, action.strategy)
    }
}
