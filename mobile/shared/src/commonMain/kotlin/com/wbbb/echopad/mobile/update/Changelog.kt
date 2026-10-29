package com.wbbb.echopad.mobile.update

import io.github.z4kn4fein.semver.toVersionOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 更新日志的一段，由发版流程切好、以 JSON 写进更新清单的 `notes`（ADR-0011）。 */
@Serializable
data class ChangelogEntry(val version: String, val date: String? = null, val body: String)

/** 更新清单 `latest.json` 里手机端用到的部分；电脑端也读同一份（docs/prd/in-app-update.md）。 */
@Serializable
internal data class UpdateManifest(val version: String, val notes: String = "")

internal val json = Json { ignoreUnknownKeys = true }

/** 从 `notes` 里挑出当前版本之后、到目标版本为止的段落（新→旧）；没写的版本自然缺席。 */
fun pickChangelog(notes: String, current: String, target: String): List<ChangelogEntry> {
    val from = current.toVersionOrNull() ?: return emptyList()
    val to = target.toVersionOrNull() ?: return emptyList()
    val entries = runCatching { json.decodeFromString<List<ChangelogEntry>>(notes) }.getOrElse { return emptyList() }
    return entries
        .mapNotNull { entry -> entry.version.toVersionOrNull()?.let { it to entry } }
        .filter { (version, _) -> version > from && version <= to }
        .sortedByDescending { (version, _) -> version }
        .map { (_, entry) -> entry }
}
