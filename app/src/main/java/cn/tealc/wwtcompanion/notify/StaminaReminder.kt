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
 *   算一次 → 排一个闹钟到「下一个提醒节点」→ 到点唤醒 → 重算 → 再排
 * ```
 *
 * # 提醒节点
 *
 * [REMINDER_LEADS_MINUTES] 是从宽到紧排列的节点列表（60 分钟、30 分钟），最后一个节点
 * 之后还有「真正回满」那一下。每个节点在一个周期内只提醒一次。想加节点，往这个列表里
 * 塞一个数就行，判定逻辑不用改。
 *
 * # 一轮（cycle）怎么界定
 *
 * 用「回满时间戳」当这一轮的身份证：只要新拿到的 `fullAtServer` 和上次不同，就说明
 * 用户花过体力、开始了新一轮，于是把「已提醒」标记全部清空。体力满着的时候时间戳是 0，
 * 此时保留旧 id，避免反复提醒「已满」。
 *
 * # 两种结束
 *
 * 体力有上限，满了就停止回复，所以**没有下一轮** —— 只有等用户花掉体力、下次查到新数据
 * 才会重新开始。因此链条一旦走到「已满」就结束，靠 App 侧每次取数成功时重新排期来复活。
 */

/** 提醒节点，从宽到紧，单位分钟。第 i 位对应 [ReminderState.firedLeadMask] 的第 i 位。 */
val REMINDER_LEADS_MINUTES: List<Long> = listOf(60L, 30L)

/** 同上，换算成毫秒 */
val REMINDER_LEADS_MILLIS: List<Long> = REMINDER_LEADS_MINUTES.map { it * 60_000L }

/** 发通知的形态 */
sealed interface ReminderKind {
    /** 进入第 [index] 个提前窗口，[leadMinutes] 是它的标称提前量 */
    data class Soon(val index: Int, val leadMinutes: Long) : ReminderKind

    /** 真正回满 */
    data object Full : ReminderKind
}

/** 持久化的提醒状态。`cycleFullAt` 同时充当这一轮的标识。 */
data class ReminderState(
    val enabled: Boolean = false,
    /** 本轮的回满时间（服务器毫秒），0 表示还没有数据 */
    val cycleFullAt: Long = 0L,
    /** 第 i 位为 1 表示 [REMINDER_LEADS_MINUTES] 的第 i 个节点已经提醒过 */
    val firedLeadMask: Int = 0,
    val firedFull: Boolean = false,
) {
    fun hasFiredLead(index: Int): Boolean = firedLeadMask and (1 shl index) != 0

    fun withFiredLead(index: Int): ReminderState = copy(firedLeadMask = firedLeadMask or (1 shl index))
}

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
    /** 提醒节点（毫秒），必须**从宽到紧**排列。默认就是 [REMINDER_LEADS_MILLIS]。 */
    val leadsMillis: List<Long> = REMINDER_LEADS_MILLIS,
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

/**
 * 如果回满时间变了，说明进入了新一轮，清空「已发过」的标记。
 *
 * `fullAtServer <= 0`（已满）时**不改** cycle id：否则体力满着期间每次查询都会被当成
 * 新一轮，导致「已满」通知反复弹。
 */
fun beginCycle(state: ReminderState, fullAtServer: Long): ReminderState =
    if (fullAtServer > 0L && fullAtServer != state.cycleFullAt) {
        state.copy(cycleFullAt = fullAtServer, firedLeadMask = 0, firedFull = false)
    } else {
        state
    }

/**
 * 核心判定。
 *
 * 优先级：已满 > 最紧迫的未提醒窗口 > 下一个节点。
 */
fun decide(state: ReminderState, input: ReminderInput): ReminderDecision {
    if (!state.enabled) return ReminderDecision.Idle

    val serverNow = input.deviceNow + input.clockOffset
    val full = input.fullAtServer

    // 已满：接口给了 0，或者算出来已经过了回满时刻，或者数值上已经顶格
    if (input.energy >= input.maxEnergy || full <= 0L || full <= serverNow) {
        return if (state.firedFull) ReminderDecision.Done
        else ReminderDecision.Publish(ReminderKind.Full, null)
    }

    // 时长与时钟偏移无关（两个时刻都平移了同样的量）
    val remaining = full - serverNow
    val leads = input.leadsMillis
    if (leads.isEmpty()) return ReminderDecision.Rearm(remaining)

    // 已经进入的最紧迫的那个窗口。列表从宽到紧，所以要取**最后**一个满足条件的：
    // 剩 20 分钟时同时落在 60 和 30 两个窗口里，紧迫的是 30 那个。
    val index = leads.indexOfLast { remaining <= it }
    if (index < 0) {
        // 还没进入任何窗口，排到最宽的那个边界
        return ReminderDecision.Rearm(remaining - leads.first())
    }

    // 下一个节点：再往里一档，最后一档之后就是「回满」（边界 0）
    val nextBoundary = leads.getOrElse(index + 1) { 0L }
    val delayToNext = remaining - nextBoundary

    return if (state.hasFiredLead(index)) {
        ReminderDecision.Rearm(delayToNext)
    } else {
        ReminderDecision.Publish(ReminderKind.Soon(index, leads[index] / 60_000L), delayToNext)
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
