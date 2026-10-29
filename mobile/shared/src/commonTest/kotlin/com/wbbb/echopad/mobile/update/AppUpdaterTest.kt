package com.wbbb.echopad.mobile.update

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {
    private fun entry(version: String) = ChangelogEntry(version, body = "- $version")

    private fun manifest(version: String, vararg entries: ChangelogEntry) =
        json.encodeToString(UpdateManifest(version, notes = json.encodeToString(entries.toList())))

    private class FakePackages(
        override val currentVersion: String = "1.0.0",
        override val installable: Boolean = true,
    ) : AppPackages {
        var manifest: () -> String = { error("network is down") }
        val fetched = mutableListOf<String>()
        val downloads = mutableListOf<String>()
        var download: suspend () -> Unit = {}
        var installResult = InstallResult.Cancelled
        val opened = mutableListOf<String>()

        override suspend fun fetchText(url: String): String {
            fetched += url
            return manifest()
        }

        override suspend fun download(url: String) {
            downloads += url
            download()
        }

        override suspend fun install() = installResult

        override fun openUrl(url: String) {
            opened += url
        }
    }

    /** 启动时的随机等待取 0，方便按时间断言。 */
    private val noDelay = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    private fun TestScope.updater(packages: AppPackages) = AppUpdater(packages, testScheduler.timeSource, noDelay)

    @Test
    fun aNewerVersionIsDownloadedAndOfferedWithItsChangelog() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.2.0", entry("1.2.0"), entry("1.0.0"), entry("1.1.0")) }
        val updater = updater(packages)

        updater.check(force = true)

        val state = updater.state.value
        assertEquals(UpdatePhase.Ready, state.phase)
        assertTrue(state.prompt)
        assertEquals("1.2.0", state.availableVersion)
        assertEquals(listOf(entry("1.2.0"), entry("1.1.0")), state.changelog)
        assertEquals(listOf(AppUpdater.MANIFEST_URL), packages.fetched)
        assertEquals(listOf(AppUpdater.apkUrl("1.2.0")), packages.downloads)
    }

    @Test
    fun theSameVersionIsUpToDate() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.0.0") }
        val updater = updater(packages)

        updater.check(force = true)

        assertEquals(UpdatePhase.UpToDate, updater.state.value.phase)
        assertFalse(updater.state.value.prompt)
        assertTrue(packages.downloads.isEmpty())
    }

    @Test
    fun aFailedCheckLooksUpToDateAndIsRetriedAfter15Minutes() = runTest {
        val packages = FakePackages()
        val updater = updater(packages)
        backgroundScope.launch { updater.checkPeriodically() }

        runCurrent()
        assertEquals(1, packages.fetched.size)
        assertEquals(UpdatePhase.UpToDate, updater.state.value.phase)

        advanceTimeBy(AppUpdater.CHECK_RETRY - 1.milliseconds)
        assertEquals(1, packages.fetched.size)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, packages.fetched.size)
    }

    @Test
    fun successfulChecksRepeatEvery4Hours() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.0.0") }
        val updater = updater(packages)
        backgroundScope.launch { updater.checkPeriodically() }

        runCurrent()
        advanceTimeBy(AppUpdater.CHECK_INTERVAL - 1.milliseconds)
        assertEquals(1, packages.fetched.size)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, packages.fetched.size)
    }

    @Test
    fun openingSettingsShortlyAfterACheckSkipsIt() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.0.0") }
        val updater = updater(packages)

        updater.check(force = true)
        updater.check(force = false)
        assertEquals(1, packages.fetched.size)

        advanceTimeBy(AppUpdater.CHECK_COOLDOWN)
        updater.check(force = false)
        assertEquals(2, packages.fetched.size)

        // 手动检查不受限。
        updater.check(force = true)
        assertEquals(3, packages.fetched.size)
    }

    @Test
    fun aDebugBuildOnlyPromptsAndOpensTheReleasePage() = runTest {
        val packages = FakePackages(installable = false)
        packages.manifest = { manifest("1.1.0") }
        val updater = updater(packages)

        updater.check(force = true)
        assertEquals(UpdatePhase.Available, updater.state.value.phase)
        assertTrue(updater.state.value.prompt)
        assertTrue(packages.downloads.isEmpty())

        // 之后检查失败也不撤回提示。
        packages.manifest = { error("network is down") }
        updater.check(force = true)
        assertEquals(UpdatePhase.Available, updater.state.value.phase)

        updater.apply()
        assertEquals(listOf(AppUpdater.releaseUrl("1.1.0")), packages.opened)
    }

    @Test
    fun aDownloadedUpdateIsNotCheckedAgain() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.1.0") }
        val updater = updater(packages)

        updater.check(force = true)
        updater.check(force = true)

        assertEquals(1, packages.fetched.size)
        assertEquals(UpdatePhase.Ready, updater.state.value.phase)
    }

    @Test
    fun aCancelledInstallKeepsTheUpdateAndAFailedOneCanBeRetriedWithoutDownloadingAgain() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.1.0") }
        val updater = updater(packages)
        updater.check(force = true)

        packages.installResult = InstallResult.Cancelled
        updater.apply()
        assertEquals(UpdatePhase.Ready, updater.state.value.phase)

        packages.installResult = InstallResult.Failed
        updater.apply()
        assertEquals(UpdatePhase.Failed, updater.state.value.phase)
        assertFalse(updater.state.value.prompt)

        updater.check(force = true)
        assertEquals(UpdatePhase.Ready, updater.state.value.phase)
        assertEquals(1, packages.downloads.size)
    }

    @Test
    fun aFailedDownloadShowsFailed() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.1.0") }
        packages.download = { error("connection reset") }
        val updater = updater(packages)

        updater.check(force = true)

        assertEquals(UpdatePhase.Failed, updater.state.value.phase)
    }

    @Test
    fun leavingTheAppMidDownloadStartsOverNextTime() = runTest {
        val packages = FakePackages()
        packages.manifest = { manifest("1.1.0") }
        val downloading = CompletableDeferred<Unit>()
        packages.download = { downloading.await() }
        val updater = updater(packages)

        val foreground = launch { updater.checkPeriodically() }
        runCurrent()
        assertEquals(UpdatePhase.Downloading, updater.state.value.phase)

        foreground.cancel()
        runCurrent()
        assertEquals(UpdatePhase.UpToDate, updater.state.value.phase)
        assertNull(updater.state.value.availableVersion)

        // 回到前台立即重新检查，不等 4 小时。
        downloading.complete(Unit)
        backgroundScope.launch { updater.checkPeriodically() }
        runCurrent()
        assertEquals(2, packages.fetched.size)
        assertEquals(UpdatePhase.Ready, updater.state.value.phase)
    }
}
