package cn.tealc.wwtcompanion.gacha

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** [GachaStore.load] 的结果：记录本体 + 读失败时的说明（正常时为 null）。 */
data class GachaLoadResult(val records: List<GachaRecord>, val error: String?)

/**
 * 抽卡记录的本地存档。
 *
 * 用两个文件：
 *  - `gacha/records.json` 记录本体
 *  - `gacha/params.json`  上次成功使用的链接参数，令牌没过期时可以直接重抓
 *
 * 写入一律先写临时文件再改名，避免中途被杀进程留下半个文件。
 */
class GachaStore(context: Context) {

    private val dir = File(context.filesDir, "gacha")
    private val recordsFile = File(dir, "records.json")
    private val paramsFile = File(dir, "params.json")

    /**
     * PC 端推过来的待导入文件。
     *
     * 用 `adb push` 塞不进来（应用私有目录），要走
     * `adb shell run-as cn.tealc.wwtcompanion` 写。tools/oauth_code.py 里有现成实现。
     * 之所以做这条路：手机连着电脑时一条命令就能导入，不用在手机上翻文件管理器。
     */
    private val dropFile = File(dir, "import.json")

    /**
     * 如果有 PC 推过来的文件就「吃掉」它并解析。
     *
     * 无论解析成功与否都会删掉，免得每次打开都重复报同一个错。
     * 没有文件返回 null；文件在但一条记录都没解析出来，会带着原因返回，
     * 而不是伪装成「导入成功 0 条」。
     */
    fun consumeDroppedImport(): GachaImportResult? {
        if (!dropFile.isFile) return null
        val text = runCatching { dropFile.readText() }.getOrNull()
        dropFile.delete()
        if (text.isNullOrBlank()) {
            return GachaImportResult(emptyList(), emptyList(), error = "推过来的文件是空的")
        }
        return runCatching { GachaJson.parseDesktopPool(text) }
            .getOrElse { e ->
                GachaImportResult(
                    emptyList(), emptyList(),
                    error = "推过来的文件解析失败：${e.message ?: e::class.java.simpleName}",
                )
            }
            .let { result ->
                // 一个卡池都没认出来（比如桌面版跑的是英文），必须说清楚是什么原因
                if (result.records.isEmpty() && result.error == null) {
                    result.copy(
                        error = if (result.unknownPools.isEmpty()) "文件里没有任何卡池数据"
                        else "卡池名一个都没认出来（${result.unknownPools.first()}…）",
                    )
                } else {
                    result
                }
            }
    }

    /**
     * 读取全部记录。
     *
     * 区分「没有文件」和「文件读不出来」：
     *  - 没有文件 → 空列表，这是正常的新装状态
     *  - 文件在但解析失败 → 把坏文件改名留档（`records.json.bad`），再返回空列表
     *
     * 留档这一步很关键：解析失败时如果不留，下一次抓取/导入会拿着空历史去合并，
     * 然后把 records.json 覆盖掉 —— 唯一的副本就没了。改名之后至少还能人工救回来。
     */
    fun load(): GachaLoadResult {
        if (!recordsFile.exists()) return GachaLoadResult(emptyList(), null)
        return try {
            GachaLoadResult(GachaJson.decode(recordsFile.readText()), null)
        } catch (e: Exception) {
            val backup = File(dir, recordsFile.name + ".bad")
            runCatching {
                if (backup.exists()) backup.delete()
                recordsFile.renameTo(backup)
            }
            GachaLoadResult(
                records = emptyList(),
                error = "存档读不出来（${e.message ?: e::class.java.simpleName}），" +
                    "已留档为 ${backup.name}，这次从空历史开始",
            )
        }
    }

    /** 写入失败返回 false（磁盘满、没权限等），由调用方决定怎么提示。 */
    fun save(records: List<GachaRecord>): Boolean =
        runCatching { writeAtomically(recordsFile, GachaJson.encode(records)) }.isSuccess

    /** 上次用的参数。没有或损坏返回 null。 */
    fun loadParams(): GachaParams? = runCatching {
        if (!paramsFile.exists()) return null
        val o = org.json.JSONObject(paramsFile.readText())
        val playerId = o.optString("playerId").takeIf { it.isNotBlank() } ?: return null
        val recordId = o.optString("recordId").takeIf { it.isNotBlank() } ?: return null
        GachaParams(
            playerId = playerId,
            recordId = recordId,
            cardPoolId = o.optString("cardPoolId"),
            serverId = o.optString("serverId"),
            languageCode = o.optString("languageCode").ifBlank { "zh-Hans" },
            serverArea = o.optString("serverArea").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    fun saveParams(params: GachaParams): Boolean {
        val json = org.json.JSONObject()
            .put("playerId", params.playerId)
            .put("recordId", params.recordId)
            .put("cardPoolId", params.cardPoolId)
            .put("serverId", params.serverId)
            .put("languageCode", params.languageCode)
            .put("serverArea", params.serverArea ?: "")
            .toString()
        return runCatching { writeAtomically(paramsFile, json) }.isSuccess
    }

    /**
     * 先写临时文件、落盘、再改名，避免中途被杀进程留下半个文件。
     *
     * 不用「改名失败就直接覆盖原文件」那种兜底：那样正好会在改名出问题时
     * 制造出它本来要防的半截文件。改名失败就抛出去，让调用方报错，
     * 原文件保持完好 —— 丢一次写入远好过丢一整份存档。
     */
    private fun writeAtomically(target: File, content: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync() // 先把数据真正刷到磁盘，再改名
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("无法替换 ${target.name}")
        }
    }
}
