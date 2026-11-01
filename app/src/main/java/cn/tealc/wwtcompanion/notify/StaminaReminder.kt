package cn.tealc.wwtcompanion.notify

/**
 * 体力提醒的纯逻辑 —— 不引用任何 Android API，所以能在 JVM 上直接单测。
 *
 * # 为什么不需要轮询
 *
 * 接口里的 `EnergyRecoverTime` 是「体力回满的绝对时间戳」，也就是说**它几点满我们是直接
 * 知道的**，不用反复去查。整个机制就是：
 *
 * ```
 *   算一次 → 排一个闹钟到「该提醒的时刻」→ 到点唤醒 → 重算 → 再排
 * ```
 *
 * # 一轮（cycle）怎么界定
 *
 * 用「回满时间戳」当这一轮的身份证：只要新拿到的 `fullAtServer` 和上次不同，就说明
 * 用户花过体力、开始了新一轮，于是把「已提醒」标记清空。体力满着的时候时间戳是 0，
 * 此时保留旧 id，避免反复提醒「已满」。
 *
 * # 两种回满
 *
 * 体力有上限，满了就停止回复，所以**没有下一轮** —— 只有等用户花掉体力、下次查到新数据
 * 才会重新开始。因此链条一旦走到「已满」就结束，靠 App 侧每次取数成功时重新排期来复活。
 */
enum class ReminderKind { SOON, FULL }

/** 持久化的提醒状态。`cycleFullAt` 同时充当这一轮的标识。 */
data class ReminderState(
    val enabled: Boolean = false,
    /** 本轮的回满时间（服务器毫秒），0 表示还没有数据 */
    val cycleFullAt: Long = 0L,
    val firedSoon: Boolean = false,
    val firedFull: Boolean = false,
)

/** 一次判定所需的当前事实 */
data class ReminderInput(
    /** 本机此刻（毫秒） */
    val deviceNow: Long,
    /** 服务器时间 − 本机时间 */
    val clockOffset: Long,
    val energy: Int,
    val maxEnergy: Int,
    /** 体力回满的服务器时间戳，0 = 已满 */
    val fullAtServer: Long,
    /** 提前多久提醒 */
    val leadMillis: Long,
)

/** 判定结果 */
sealed interface ReminderDecision {
    /** 开关关着，或没有可用数据 */
    data object Idle : ReminderDecision

    /** 发一条通知；[nextDelayMs] 为 null 表示之后不再排期 */
    data class Publish(val kind: ReminderKind, val nextDelayMs: Long?) : ReminderDecision

    /** 不发通知，只排下一次唤醒 */
    data class Rearm(val delayMs: Long) : ReminderDecision

    /** 这一轮已经结束（已满且提醒过），不再排期 */
    data object Done : ReminderDecision
}

/** 默认提前 30 分钟 */
const val DEFAULT_LEAD_MILLIS: Long = 30L * 60_000L

/**
 * 如果回满时间变了，说明进入了新一轮，清空「已发过」的标记。
 *
 * `fullAtServer <= 0`（已满）时**不改** cycle id：否则体力满着期间每次查询都会被当成
 * 新一轮，导致「已满」通知反复弹。
 */
fun beginCycle(state: ReminderState, fullAtServer: Long): ReminderState =
    if (fullAtServer > 0L && fullAtServer != state.cycleFullAt) {
        state.copy(cycleFullAt = fullAtServer, firedSoon = false, firedFull = false)
    } else {
        state
    }

/**
 * 核心判定。
 *
 * 优先级：已满 > 进入提前窗口 > 还早。
 */
fun decide(state: ReminderState, input: ReminderInput): ReminderDecision {
    if (!state.enabled) return ReminderDecision.Idle

    val serverNow = input.deviceNow + input.clockOffset
    val full = input.fullAtServer

    // 已满：接口给了 0，或者算出来已经过了回满时刻，或者数值上已经顶格
    if (input.energy >= input.maxEnergy || full <= 0L || full <= serverNow) {
        return if (state.firedFull) ReminderDecision.Done
        else ReminderDecision.Publish(ReminderKind.FULL, null)
    }

    // 时长与时钟偏移无关（两个时刻都平移了同样的量），直接在本机坐标系里算
    val remaining = full - serverNow
    return when {
        remaining <= input.leadMillis ->
            if (state.firedSoon) ReminderDecision.Rearm(remaining)
            else ReminderDecision.Publish(ReminderKind.SOON, remaining)
        else -> ReminderDecision.Rearm(remaining - input.leadMillis)
    }
}

/**
 * 后台刷新失败（多半是 oauthCode 过期）时的保守处理。
 *
 * 只用上一轮存下来的 `cycleFullAt` 判断：**不发任何通知**，最多把闹钟重新排到回满时刻。
 * 宁可漏一条，也不根据可能已经过时的预测去打扰用户；失败原因会显示在设置页里。
 *
 * @return 需要重排的毫秒数，null 表示链条就此停下
 */
fun decideOffline(state: ReminderState, deviceNow: Long, clockOffset: Long): Long? {
    if (!state.enabled || state.cycleFullAt <= 0L) return null
    val serverNow = deviceNow + clockOffset
    val remaining = state.cycleFullAt - serverNow
    return if (remaining > 0L) remaining else null
}
