package com.wbbb.echopad.mobile.update

import io.github.z4kn4fein.semver.toVersionOrNull
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UpdatePhase {
    /** 已是最新，检查失败时也显示为这个状态。 */
    UpToDate,
    Checking,
    /** debug 构建发现了新版本：只提示，不下载。 */
    Available,
    Downloading,
    /** 新版本已下载好，等用户确认安装。 */
    Ready,
    /** 下载或安装失败，可以重新检查。 */
    Failed,
}

data class UpdateState(
    val phase: UpdatePhase,
    val currentVersion: String,
    val installable: Boolean,
    val availableVersion: String? = null,
    /** 当前版本之后、到新版本为止的更新日志（新→旧）。 */
    val changelog: List<ChangelogEntry> = emptyList(),
) {
    /** 是否提示新版本可用：可以安装，或 debug 构建发现了新版本。提示不能关闭。 */
    val prompt: Boolean get() = phase == UpdatePhase.Ready || phase == UpdatePhase.Available
}

/**
 * 应用内更新（docs/prd/in-app-update.md、ADR-0009）：App 在前台时定时检查最新的 GitHub Release，
 * 发现新版本就下载，用户确认后交给系统安装。debug 构建只提示，确认后打开 Release 页面。
 */
class AppUpdater(
    private val packages: AppPackages,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(
        UpdateState(UpdatePhase.UpToDate, packages.currentVersion, installable = packages.installable),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 同一时间只做一次检查；并发的请求等它结束后直接返回。 */
    private val checking = Mutex()
    private var lastCheck: ComparableTimeMark? = null
    private var lastCheckFailed = false
    private var downloadedVersion: String? = null

    /** App 在前台期间运行：进入前台稍等一会儿，之后每 4 小时检查一次，检查失败时 15 分钟后重试。 */
    suspend fun checkPeriodically(): Nothing {
        delay(STARTUP_DELAY_MAX * random.nextDouble())
        while (true) {
            lastCheck?.let { last ->
                delay(last + (if (lastCheckFailed) CHECK_RETRY else CHECK_INTERVAL) - timeSource.markNow())
            }
            check(force = true)
        }
    }

    /** 检查一次；[force] 为 false 时（打开设置）距上次检查不足 5 分钟就跳过。 */
    suspend fun check(force: Boolean) {
        if (!checking.tryLock()) {
            checking.withLock {}
            return
        }
        try {
            val last = lastCheck
            if (!force && last != null && last.elapsedNow() < CHECK_COOLDOWN) return
            lastCheck = timeSource.markNow()
            // 新版本已下好就等安装，下次打开 App 再查更新的版本。
            if (_state.value.phase == UpdatePhase.Ready) return
            // debug 构建已提示新版本时再检查，不把提示撤回。
            if (_state.value.phase != UpdatePhase.Available) _state.update { it.copy(phase = UpdatePhase.Checking) }

            val manifest = try {
                json.decodeFromString<UpdateManifest>(packages.fetchText(MANIFEST_URL))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lastCheckFailed = true
                settleUpToDate()
                return
            }
            lastCheckFailed = false
            if (isNewer(manifest.version)) offer(manifest) else settleUpToDate()
        } finally {
            // 检查或下载到一半 App 进入后台时，回到已是最新，下次进入前台重新检查。
            val phase = _state.value.phase
            if (phase == UpdatePhase.Checking || phase == UpdatePhase.Downloading) {
                lastCheck = null
                settleUpToDate()
            }
            checking.unlock()
        }
    }

    /** 用户在更新弹窗里确认：交给系统安装；debug 构建打开该版本的 Release 页面。 */
    suspend fun apply() {
        val state = _state.value
        val version = state.availableVersion ?: return
        if (!packages.installable) {
            packages.openUrl(releaseUrl(version))
            return
        }
        if (state.phase != UpdatePhase.Ready) return
        when (packages.install()) {
            InstallResult.Installed, InstallResult.Cancelled -> Unit
            InstallResult.Failed -> _state.update { it.copy(phase = UpdatePhase.Failed) }
        }
    }

    private fun isNewer(version: String): Boolean {
        val current = packages.currentVersion.toVersionOrNull() ?: return false
        return version.toVersionOrNull()?.let { it > current } ?: false
    }

    private suspend fun offer(manifest: UpdateManifest) {
        val offered = _state.value.copy(
            availableVersion = manifest.version,
            changelog = pickChangelog(manifest.notes, packages.currentVersion, manifest.version),
        )
        when {
            !packages.installable -> _state.value = offered.copy(phase = UpdatePhase.Available)
            // 安装失败后再检查到同一个版本，不用重新下载。
            downloadedVersion == manifest.version -> _state.value = offered.copy(phase = UpdatePhase.Ready)
            else -> {
                _state.value = offered.copy(phase = UpdatePhase.Downloading)
                _state.value = try {
                    packages.download(apkUrl(manifest.version))
                    downloadedVersion = manifest.version
                    offered.copy(phase = UpdatePhase.Ready)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    offered.copy(phase = UpdatePhase.Failed)
                }
            }
        }
    }

    /** 没有更新或检查失败：显示为已是最新，但 debug 构建已有的更新提示不撤回。 */
    private fun settleUpToDate() {
        if (_state.value.phase == UpdatePhase.Available) return
        _state.update { it.copy(phase = UpdatePhase.UpToDate, availableVersion = null, changelog = emptyList()) }
    }

    companion object {
        val STARTUP_DELAY_MAX = 30.seconds
        val CHECK_INTERVAL = 4.hours
        val CHECK_RETRY = 15.minutes
        val CHECK_COOLDOWN = 5.minutes

        private const val RELEASES = "https://github.com/WBBB0730/EchoPad/releases"
        const val MANIFEST_URL = "$RELEASES/latest/download/latest.json"

        /** Release 上的 APK 名字是固定格式（见 .github/workflows/release.yml）。 */
        fun apkUrl(version: String) = "$RELEASES/download/v$version/EchoPad-$version.apk"

        fun releaseUrl(version: String) = "$RELEASES/tag/v$version"
    }
}
