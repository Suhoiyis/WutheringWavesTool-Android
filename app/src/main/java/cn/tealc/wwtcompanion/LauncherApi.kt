package cn.tealc.wwtcompanion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** 查询结果 */
sealed interface ApiResult {
    /**
     * @param clockOffsetMillis 服务器时间 - 本机时间（毫秒）。倒计时用的是服务器给的
     *   绝对时间戳，加上这个偏移量再和本机时钟比较，就不怕手机时钟不准。
     */
    data class Success(
        val data: AccountData,
        val clockOffsetMillis: Long,
    ) : ApiResult

    data class Failure(val message: String) : ApiResult
    data object TokenExpired : ApiResult
}

/**
 * 库洛官方 PC 启动器 SDK 接口客户端。
 *
 * 对应桌面端的 LauncherManager:
 *   1) queryPlayerInfo 用 oauthCode 换 roleId + 区服
 *   2) queryRole      用 roleId + 区服换 Base / BattlePass 详细数据
 *
 * 港服走 .net 域名，区服键为 HMT（由 roleId 首字符 8 决定）。
 */
object LauncherApi {

    private const val HOST = "https://pc-launcher-sdk-api.kurogame.net"
    private const val MAX_RETRY = 5          // code=1005 时的重试次数
    private const val RETRY_DELAY_MS = 500L  // 重试间隔

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 单次请求的总上限。只有 connect/read 超时的话，服务端慢速滴数据
        // （每次间隔都刚好小于 readTimeout）能把一个请求吊住不返回。
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 主入口：用 oauthCode 拉取账号概览 */
    suspend fun fetchAccount(oauthCode: String): ApiResult = withContext(Dispatchers.IO) {
        try {
            // ① queryPlayerInfo -> roleId / 区服
            val infoResp = post("$HOST/game/queryPlayerInfo", JSONObject().put("oauthCode", oauthCode))
            codeError(infoResp)?.let { return@withContext it }

            val infoData = infoResp.optJSONObject("data")
                ?: return@withContext ApiResult.Failure("queryPlayerInfo 返回 data 为空")
            val region = infoData.keys().asSequence().firstOrNull()
                ?: return@withContext ApiResult.Failure("无法识别区服")
            val playerInfo = JSONObject(infoData.getString(region))
            val roleId = playerInfo.optString("roleId")
            if (roleId.isBlank()) return@withContext ApiResult.Failure("roleId 为空")

            // ② queryRole -> Base / BattlePass
            val roleResp = post(
                "$HOST/game/queryRole",
                JSONObject()
                    .put("oauthCode", oauthCode)
                    .put("playerId", roleId)
                    .put("region", region),
            )
            codeError(roleResp)?.let { return@withContext it }
            // 响应里的 timestamp 是服务器此刻的毫秒时间；拿它和本机时钟一比，
            // 就知道手机快/慢了多少，后面所有倒计时都用它校正。
            val deviceAtResponse = System.currentTimeMillis()
            val serverTime = roleResp.optLong("timestamp")
            val clockOffset = if (serverTime > 0L) serverTime - deviceAtResponse else 0L

            val roleData = roleResp.optJSONObject("data")
                ?: return@withContext ApiResult.Failure("queryRole 返回 data 为空")
            val dataKey = roleData.keys().asSequence().firstOrNull()
                ?: return@withContext ApiResult.Failure("角色数据缺少区服键")
            val pd = JSONObject(roleData.getString(dataKey))
            val base = pd.optJSONObject("Base")
                ?: return@withContext ApiResult.Failure("缺少 Base 节点")
            val bp = pd.optJSONObject("BattlePass") ?: JSONObject()

            ApiResult.Success(
                data = AccountData(
                    roleId = roleId,
                    roleName = playerInfo.optString("roleName"),
                    region = region,
                    level = base.optInt("Level"),
                    worldLevel = base.optInt("WorldLevel"),
                    activeDays = base.optInt("ActiveDays"),
                    energy = base.optInt("Energy"),
                    maxEnergy = base.optInt("MaxEnergy"),
                    energyRecoverTime = base.optLong("EnergyRecoverTime"),
                    storeEnergy = base.optInt("StoreEnergy"),
                    maxStoreEnergy = base.optInt("MaxStoreEnergy"),
                    storeEnergyRecoverTime = base.optLong("StoreEnergyRecoverTime"),
                    liveness = base.optInt("Liveness"),
                    livenessMax = base.optInt("LivenessMaxCount"),
                    weeklyInstCount = base.optInt("WeeklyInstCount"),
                    battlePassLevel = bp.optInt("Level"),
                    battlePassWeekExp = bp.optInt("WeekExp"),
                    battlePassWeekMaxExp = bp.optInt("WeekMaxExp"),
                    chestBoxes = readBoxes(base.optJSONObject("BasicBoxes"), 4),
                    phantomBoxes = readBoxes(base.optJSONObject("PhantomBoxes"), 3),
                ),
                clockOffsetMillis = clockOffset,
            )
        } catch (e: CancellationException) {
            // 取消是控制流，不是网络错误。当成 Failure 返回会让取消失效
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(e.message ?: e.toString())
        }
    }

    /** 业务错误码 -> 结果；返回 null 表示正常 */
    private fun codeError(json: JSONObject): ApiResult? = when (json.optInt("code")) {
        200, 0 -> null
        1001 -> ApiResult.TokenExpired
        else -> ApiResult.Failure("接口错误 ${json.optInt("code")}: ${json.optString("message")}")
    }

    /** 把 {"1":n,"2":n,...} 读成按序列表；缺失的补 0 */
    private fun readBoxes(obj: JSONObject?, count: Int): List<Int> =
        (1..count).map { obj?.optInt(it.toString()) ?: 0 }

    /** POST，并对 code=1005（瞬时）做重试 */
    private fun post(url: String, body: JSONObject): JSONObject {
        var json = postOnce(url, body)
        var retries = 0
        while (json.optInt("code") == 1005 && retries < MAX_RETRY) {
            Thread.sleep(RETRY_DELAY_MS)
            json = postOnce(url, body)
            retries++
        }
        return json
    }

    private fun postOnce(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body.string()
            if (text.isBlank()) throw IllegalStateException("空响应 HTTP ${resp.code}")
            return JSONObject(text)
        }
    }
}
