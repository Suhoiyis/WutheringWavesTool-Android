package cn.tealc.wwtcompanion.gacha

import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 从抽卡链接里解析出来的请求参数。
 *
 * 这些参数**同时就是凭证** —— 官方抽卡接口不校验登录态，`recordId` 就是令牌。
 * 所以这一整套流程跟 `oauthCode` 完全无关。
 */
data class GachaParams(
    val playerId: String,
    val recordId: String,
    /**
     * URL 里的 `resources_id`。
     *
     * **接口根本不看这个字段** —— 实测把它删掉、留空、填成垃圾串，
     * 返回的数据一模一样。留着纯粹是为了存档和排查问题。
     */
    val cardPoolId: String = "",
    /** URL 里的 `svr_id`。留空时会由 [GachaServer] 从 [playerId] 推出来。 */
    val serverId: String = "",
    val languageCode: String = "zh-Hans",
    /** URL 里的 `svr_area`，用来判断国服/国际服；老链接可能没有。 */
    val serverArea: String? = null,
)

/**
 * 区服 ID 对照表。
 *
 * 抽卡接口必填 `serverId`，但它**不要求是账号自己的那个**：实测填任意一个已知区服，
 * 拿回的数据完全一样（600 抽一条不差）—— 数据是由 `recordId` 决定的，`serverId`
 * 的作用更像是「挑一个能应答的机柜」。
 *
 * 所以只要能从别的信息里推出一个合法值就够了。这张表来自 wuwatracker 的前端
 * （webpack 模块 `14435`），键是 **playerId 的首位数字**：
 * 我们账号 `802104525` 首位是 8 → `919752ae…`，和链接里的 `svr_id` 分毫不差。
 *
 * 顺带一提，桌面端 `LauncherApi` 早就在用「roleId 首字符 8 → 港服」这条规律了。
 */
object GachaServer {

    /** playerId 首位数字 -> 区服 ID */
    private val BY_FIRST_DIGIT: Map<Char, String> = mapOf(
        '1' to "76402e5b20be2c39f095a152090afddc",
        '5' to "591d6af3a3090d8ea00d8f86cf6d7501",
        '6' to "6eb2a235b30d05efd77bedb5cf60999e",
        '7' to "86d52186155b148b5c138ceb41be9650",
        '8' to "919752ae5ea09c1ced910dd668a63ffb",
        '9' to "10cd7254d57e58ae560b15d51e34b4c8",
    )

    /** 猜不出来时挨个试的候选（国际服实测都能查到数据）。 */
    val FALLBACKS: List<String> = listOf(
        "919752ae5ea09c1ced910dd668a63ffb",
        "591d6af3a3090d8ea00d8f86cf6d7501",
        "6eb2a235b30d05efd77bedb5cf60999e",
        "86d52186155b148b5c138ceb41be9650",
        "10cd7254d57e58ae560b15d51e34b4c8",
    )

    /** 首位数字没见过就返回 null，交给 [GachaApi] 去挨个试。 */
    fun serverIdFor(playerId: String): String? =
        playerId.trim().firstOrNull()?.let { BY_FIRST_DIGIT[it] }
}

/**
 * 抽卡链接解析。
 *
 * 用户可能粘进来的东西五花八门：整行日志、纯 URL、只带参数的半截串，
 * 甚至末尾粘上了日志里的引号或方括号。这里统一按「先找 `#/record?`，
 * 找不到就当成参数串」处理。
 */
object GachaUrlParser {

    private val PARAM_REGEX = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([^&\s"'\]]+)""")
    private const val RECORD_MARK = "#/record?"
    private val RECORD_ID_REGEX = Regex("""^[0-9a-f]{32}$""")

    /** 解析失败返回 null。 */
    fun parse(raw: String): GachaParams? {
        val text = unescapeUnicode(raw.trim())
        val query = extractQuery(text) ?: return null
        val map = PARAM_REGEX.findAll(query).associate { it.groupValues[1] to it.groupValues[2] }

        val playerId = map["player_id"]?.takeIf { it.isNotBlank() } ?: return null
        val recordId = map["record_id"]?.takeIf { it.isNotBlank() } ?: return null

        return GachaParams(
            playerId = playerId,
            recordId = recordId,
            // 这两个都不是必需的：resources_id 接口压根不读，svr_id 可以从 playerId 推。
            // 于是链接被截断、或者只剩下半截参数串时，照样能用。
            cardPoolId = map["resources_id"]?.takeIf { it.isNotBlank() }.orEmpty(),
            serverId = map["svr_id"]?.takeIf { it.isNotBlank() }
                ?: GachaServer.serverIdFor(playerId).orEmpty(),
            // 实测缺了 languageCode 接口会返回空数据，所以给个默认值
            languageCode = map["lang"]?.takeIf { it.isNotBlank() } ?: "zh-Hans",
            serverArea = map["svr_area"],
        )
    }

    /**
     * 比 [parse] 再宽松一档：**只给一个 record_id 也行**。
     *
     * 这时需要 [fallbackPlayerId]（玩家自己的 UID，账号界面上就有）。
     * 有了 UID 连区服都能推出来，于是原本四个参数里，只剩 record_id
     * 是只有用户才知道的 —— 这正是 wuwatracker 让 PS5 玩家只填两个框的原理。
     */
    fun parseLoose(raw: String, fallbackPlayerId: String? = null): GachaParams? {
        parse(raw)?.let { return it }
        val recordId = bareRecordId(raw) ?: return null
        val playerId = fallbackPlayerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return GachaParams(
            playerId = playerId,
            recordId = recordId,
            serverId = GachaServer.serverIdFor(playerId).orEmpty(),
        )
    }

    /** 用户多半会连着引号、空格、方括号一起复制进来，这里都擦掉。 */
    private fun bareRecordId(raw: String): String? = normalize(raw)
        .takeIf { RECORD_ID_REGEX.matches(it) }

    /** 判断用户粘的是不是一个光秃秃的 record_id（用来给出更准确的报错）。 */
    fun looksLikeBareRecordId(raw: String): Boolean =
        RECORD_ID_REGEX.matches(normalize(raw))

    private fun normalize(raw: String): String = raw.trim()
        .trim('"', '\'', '[', ']', '<', '>', ',', ';')
        .filterNot { it.isWhitespace() }
        .lowercase()

    private fun extractQuery(text: String): String? {
        val idx = text.indexOf(RECORD_MARK)
        if (idx >= 0) return text.substring(idx + RECORD_MARK.length)
        // 直接粘参数串的情况
        return if (text.contains("player_id=") || text.contains("record_id=")) text else null
    }

    /** 日志里的 `&` 可能被转义成 `\u0026`。 */
    private fun unescapeUnicode(s: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""").replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
}

/**
 * 把参数还原成一条完整的抽卡链接。
 *
 * 用途：只贴了 `record_id` 的话，手上就没有一条「完整链接」了，而别的统计工具
 * 往往只认链接。这里照着游戏自己的格式把它拼回去。
 *
 * 链接长这样（顺序和真实链接保持一致）：
 * ```
 * https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html#/record
 *   ?svr_id=…&player_id=…&lang=zh-Hans&gacha_id=100084&gacha_type=1
 *   &svr_area=global&record_id=…&resources_id=…&platform=PC
 * ```
 *
 * `gacha_id` / `gacha_type` / `resources_id` / `platform` 是**照格式补的占位值**：
 * 官方接口完全不看它们（实测删掉、填垃圾串返回的数据一模一样），桌面版
 * `GachaLogUtil.getParamFromUrl` 也只用 player_id / record_id / resources_id /
 * gacha_type / svr_id / lang 六个字段，而且拿到 cardPoolType 之后还会按卡池覆盖掉。
 * 拼上纯粹是为了让挑剔的解析器不至于因为缺字段而拒收。
 */
object GachaUrlBuilder {

    /** 国际服/港服的记录页。这条是从真实链接里抄下来的。 */
    private const val GLOBAL_PAGE =
        "https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html#/record"

    /**
     * 国服的记录页。
     *
     * **未实测** —— 手上只有港服账号，域名和 `svr_area=cn` 都是按国际服的形式类推的。
     * 解析参数的工具只从 `?` 往后读，域名对不对一般不影响使用。
     */
    private const val CN_PAGE =
        "https://aki-gm-resources.aki-game.com/aki/gacha/index.html#/record"

    /** 真实链接里固定出现的两个值。 */
    private const val GACHA_ID = "100084"
    private const val GACHA_TYPE = "1"

    /** `resources_id` 猜不出来时的占位值：真实链接里出现过的角色活动池 banner。 */
    private const val DEFAULT_RESOURCES_ID = "4e72d71659676be7c39c94f18f807740"

    /** 链接里是否要带上 `platform`（我们无从知道真实值，补个 PC）。 */
    private const val PLATFORM = "PC"

    /**
     * 拼一条完整链接。除了 [GachaParams] 里已有的字段，其余都用上面的占位值补。
     *
     * `serverId` 为空时会尝试从 `playerId` 首位推出来；连区服都推不出来时，
     * 该参数会被省略，而不是塞一个错的进去。
     */
    fun build(params: GachaParams): String {
        val global = GachaApi.isGlobal(params)
        val serverId = params.serverId.takeIf { it.isNotBlank() }
            ?: GachaServer.serverIdFor(params.playerId).orEmpty()

        // 顺序照真实链接来，别人肉眼看也顺眼
        val query = buildList {
            if (serverId.isNotBlank()) add("svr_id" to serverId)
            add("player_id" to params.playerId)
            add("lang" to params.languageCode.ifBlank { "zh-Hans" })
            add("gacha_id" to GACHA_ID)
            add("gacha_type" to GACHA_TYPE)
            add("svr_area" to if (global) "global" else "cn")
            add("record_id" to params.recordId)
            add("resources_id" to params.cardPoolId.ifBlank { DEFAULT_RESOURCES_ID })
            add("platform" to PLATFORM)
        }.joinToString("&") { (k, v) -> "$k=$v" }

        return (if (global) GLOBAL_PAGE else CN_PAGE) + "?" + query
    }
}

/** 拉取结果。 */
sealed interface GachaFetchResult {
    /**
     * @param failedPools 这次没拿到数据的卡池（其余池的数据是有效的，照常保存）
     */
    data class Success(
        val records: List<GachaRecord>,
        val failedPools: List<GachaPool> = emptyList(),
    ) : GachaFetchResult

    /** 链接无效或已过期 —— 需要重新去游戏里打开一次唤取记录页。 */
    data class InvalidToken(val reason: String) : GachaFetchResult

    /** 网络等异常。 */
    data class Failure(val message: String) : GachaFetchResult
}

/**
 * 官方抽卡记录接口。
 *
 * 一次完整抓取要对 13 个卡池各发一次 POST，接口没有批量查询。
 */
object GachaApi {

    private const val CN_HOST = "https://gmserver-api.aki-game2.com"
    private const val GLOBAL_HOST = "https://gmserver-api.aki-game2.net"
    private const val PATH = "/gacha/record/query"

    /** 池与池之间稍微歇一下，别把人家接口打急了。 */
    private const val POOL_INTERVAL_MS = 120L

    /**
     * 连着这么多个池都是传输层失败（连不上、超时）就放弃。
     *
     * 没有这个的话：手机连着没有出口的 WiFi 时，13 个池各自死等 connectTimeout，
     * 用户要盯着转圈四分多钟才看到报错。前几个池全挂且一个都没成功，基本可以断定
     * 是网的问题，没必要把 13 个都试完。
     */
    private const val MAX_CONSECUTIVE_ERRORS = 3

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 单次请求的总上限，防止服务端慢速滴数据把请求吊住
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 国际服/国服的判定：`svr_area` 优先，没有就退回桌面版的经验规则（国服 playerId 以 1 开头）。 */
    internal fun isGlobal(params: GachaParams): Boolean = when (val area = params.serverArea?.lowercase()) {
        null, "" -> !params.playerId.startsWith("1")
        "global" -> true
        else -> false
    }

    /** 国际服走 `.net`，国服走 `.com`。 */
    internal fun endpointFor(params: GachaParams): String =
        (if (isGlobal(params)) GLOBAL_HOST else CN_HOST) + PATH

    /**
     * 依次查询全部 13 个卡池。
     *
     * @param onProgress 进度回调（已完成池数, 总数），在 IO 线程调用
     */
    suspend fun fetch(
        params: GachaParams,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): GachaFetchResult = withContext(Dispatchers.IO) {
        val url = endpointFor(params)
        // 只拿到 record_id 时 serverId 是空的，这里补上
        val serverId = params.serverId.takeIf { it.isNotBlank() }
            ?: resolveServerId(url, params)
            ?: return@withContext GachaFetchResult.Failure("无法确定区服，请粘贴一次完整链接")
        val params = params.copy(serverId = serverId)
        val pools = GachaPool.entries.sortedBy { it.id }
        val collected = mutableListOf<GachaRecord>()
        val failed = mutableListOf<GachaPool>()
        var succeeded = 0
        var rejectionReason: String? = null
        var lastError: String? = null
        var consecutiveErrors = 0

        pools.forEachIndexed { index, pool ->
            onProgress(index, pools.size)
            when (val result = queryPool(url, params, pool)) {
                is PoolResult.Ok -> {
                    succeeded++
                    consecutiveErrors = 0
                    collected += result.records
                }
                is PoolResult.Rejected -> rejectionReason = result.reason
                is PoolResult.Error -> {
                    failed += pool
                    lastError = result.message
                    consecutiveErrors++
                    // 一个都没成功就已经连挂几个，别再让用户干等剩下的池了
                    if (succeeded == 0 && consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        return@withContext GachaFetchResult.Failure(
                            "网络连不上（连试 $consecutiveErrors 个卡池都失败）：${result.message}"
                        )
                    }
                }
            }
            if (index < pools.lastIndex) delay(POOL_INTERVAL_MS)
        }
        onProgress(pools.size, pools.size)

        // 一个池都没成功时，看看是「令牌被拒」还是「网络不通」：
        // 前者要提示用户去游戏里重新取链接，后者提示检查网络
        val rejected = rejectionReason
        return@withContext when {
            succeeded == 0 && rejected != null -> GachaFetchResult.InvalidToken(rejected)
            succeeded == 0 -> GachaFetchResult.Failure(lastError ?: "所有卡池都没能取到数据")
            else -> GachaFetchResult.Success(collected, failed)
        }
    }

    private sealed interface PoolResult {
        data class Ok(val records: List<GachaRecord>) : PoolResult
        data class Rejected(val reason: String) : PoolResult
        data class Error(val message: String) : PoolResult
    }

    /**
     * playerId 首位数字没在表里时的兜底：挨个区服拿 1 号池试一下，
     * 谁查得出数据就用谁。只有猜不出区服时才会走到这里。
     *
     * 「答得上话」就算数，**不要求 1 号池里真的有记录** —— 没抽过角色活动池的账号
     * 那里本来就是空的，如果要求非空，5 个候选区服会全部落选，最后报「无法确定区服」，
     * 而其实随便哪个都能用。
     */
    private fun resolveServerId(url: String, params: GachaParams): String? {
        val first = GachaPool.entries.minByOrNull { it.id } ?: return null
        return GachaServer.FALLBACKS.firstOrNull { candidate ->
            queryPool(url, params.copy(serverId = candidate), first) is PoolResult.Ok
        }
    }

    private fun queryPool(url: String, params: GachaParams, pool: GachaPool): PoolResult {
        val body = JSONObject()
            .put("playerId", params.playerId)
            .put("recordId", params.recordId)
            .put("cardPoolId", params.cardPoolId)
            .put("cardPoolType", pool.id.toString())
            .put("serverId", params.serverId)
            .put("languageCode", params.languageCode)

        return try {
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    return PoolResult.Error("HTTP ${resp.code}")
                }
                if (text.isBlank()) {
                    return PoolResult.Error("空响应")
                }
                val json = JSONObject(text)
                val code = json.optInt("code")
                if (code == 0) {
                    val array = json.optJSONArray("data")
                    PoolResult.Ok(if (array == null) emptyList() else GachaJson.parseRecords(pool.id, array))
                } else {
                    // message 可能是 JSON null，optString 会给出字面量 "null"
                    val message = json.optTextOrNull("message") ?: "code=$code"
                    PoolResult.Rejected(message)
                }
            }
        } catch (e: CancellationException) {
            // 取消是控制流，不是错误。吞掉它会让协程停不下来
            // （用户退出抽卡页后这一趟请求还在后台跑完）
            throw e
        } catch (e: Exception) {
            PoolResult.Error(e.message ?: e.toString())
        }
    }
}
