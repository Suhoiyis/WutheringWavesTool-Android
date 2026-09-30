package cn.tealc.wwtcompanion.gacha

import org.json.JSONArray
import org.json.JSONObject

/**
 * 读一个可能缺失、也可能是 JSON `null` 的字符串字段；没有就返回 null。
 *
 * 不能直接用 `optString`：Android 自带的 org.json 在字段是 JSON `null` 时返回的是
 * **字符串 "null"**（`JSONObject.NULL.toString()`），而不是空串，于是
 * `{"name":null}` 会被当成一个真名叫 "null" 的记录存下来。
 * 桌面 JDK 上的 org.json 行为不一样（返回空串），所以单元测试抓不到这个坑。
 */
internal fun JSONObject.optTextOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

/**
 * 导入结果。
 *
 * 认不出来的卡池名、以及「一条都没解析出来」的原因都要带出来，
 * 不能悄悄丢掉 —— 否则界面只能说一句「已导入 0 条」，用户完全不知道为什么。
 */
data class GachaImportResult(
    val records: List<GachaRecord>,
    val unknownPools: List<String>,
    val error: String? = null,
)

/**
 * 抽卡数据的 JSON 读写。
 *
 * 涉及三种格式：
 *  1. 官方接口返回的 `data` 数组
 *  2. 桌面版的 `data/<playerId>/pool.json`（以卡池中文名为键）
 *  3. 本应用自己的存档（带版本号，便于以后改结构）
 */
object GachaJson {

    private const val KEY_VERSION = "version"
    private const val KEY_RECORDS = "records"
    private const val CURRENT_VERSION = 1

    /**
     * 解析一个记录数组（官方接口的 `data`，或 pool.json 里某个卡池的值）。
     *
     * @param poolId 这批记录属于哪个卡池
     */
    fun parseRecords(poolId: Int, array: JSONArray): List<GachaRecord> =
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { parseRecord(poolId, it) }
        }

    private fun parseRecord(poolId: Int, o: JSONObject): GachaRecord? {
        // 字段不全、或者字段是 JSON null 的记录直接跳过（optTextOrNull 会把 null 当没有）
        val time = o.optTextOrNull("time") ?: return null
        val name = o.optTextOrNull("name") ?: return null
        return GachaRecord(
            poolId = poolId,
            cardPoolType = o.optTextOrNull("cardPoolType").orEmpty(),
            resourceId = o.optInt("resourceId"),
            qualityLevel = o.optInt("qualityLevel"),
            resourceType = o.optTextOrNull("resourceType").orEmpty(),
            name = name,
            count = o.optInt("count", 1).coerceAtLeast(1),
            time = time,
        )
    }

    /**
     * 解析桌面版的 `pool.json`。
     *
     * 它的结构是 `{ "角色活动唤取": [ {...}, ... ], ... }`，键就是卡池中文名。
     * 认不出的键会收集到 [GachaImportResult.unknownPools] 里而不是被静默丢弃，
     * 这样界面能提示「有 N 个卡池没认出来」。
     */
    fun parseDesktopPool(json: String): GachaImportResult {
        val root = JSONObject(json)
        val records = mutableListOf<GachaRecord>()
        val unknown = mutableListOf<String>()
        for (name in root.keys()) {
            val pool = GachaPool.byDisplayName(name)
            val array = root.optJSONArray(name) ?: continue
            if (pool == null) {
                // 只在这池真的有数据时才值得抱怨
                if (array.length() > 0) unknown += name
                continue
            }
            records += parseRecords(pool.id, array)
        }
        return GachaImportResult(sortNewestFirst(records), unknown)
    }

    /** 序列化成应用自己的存档格式。 */
    fun encode(records: List<GachaRecord>): String {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject()
                    .put("poolId", r.poolId)
                    .put("cardPoolType", r.cardPoolType)
                    .put("resourceId", r.resourceId)
                    .put("qualityLevel", r.qualityLevel)
                    .put("resourceType", r.resourceType)
                    .put("name", r.name)
                    .put("count", r.count)
                    .put("time", r.time)
            )
        }
        return JSONObject()
            .put(KEY_VERSION, CURRENT_VERSION)
            .put(KEY_RECORDS, array)
            .toString()
    }

    /** 反序列化。数据损坏时抛异常，由调用方决定怎么兜底。 */
    fun decode(json: String): List<GachaRecord> {
        val root = JSONObject(json)
        val array = root.optJSONArray(KEY_RECORDS) ?: return emptyList()
        return sortNewestFirst(parseRecordsWithEmbeddedPoolId(array))
    }

    /** 存档里每条记录自带 poolId（不像 pool.json 是靠键推出来的）。 */
    private fun parseRecordsWithEmbeddedPoolId(array: JSONArray): List<GachaRecord> =
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { parseRecord(it.optInt("poolId"), it) }
        }

    /** 每个卡池内按时间倒序，和接口顺序保持一致。 */
    private fun sortNewestFirst(records: List<GachaRecord>): List<GachaRecord> =
        records.groupBy { it.poolId }
            .toSortedMap()
            .flatMap { (_, list) -> list.sortedByDescending { it.time } }
}
