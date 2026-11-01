package cn.tealc.wwtcompanion.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.tealc.wwtcompanion.ApiResult
import cn.tealc.wwtcompanion.CredentialStore
import cn.tealc.wwtcompanion.LauncherApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 闹钟到点时被系统唤醒，重新核对一次体力，再决定发通知还是继续排下一轮。
 *
 * 广播接收器只有约 10 秒可用，所以这里用 [goAsync] 把网络请求挪到后台协程，
 * 并把调用压到 8 秒上限 —— [LauncherApi] 自己的超时有 30 秒，对接收器太长了。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                evaluate(app)
            } catch (e: Exception) {
                ReminderStore(app).setLastSkipReason("后台检查异常：${e.message ?: e.javaClass.simpleName}")
            } finally {
                pending.resultCode = 0
                pending.finish()
            }
        }
    }
}

/** 开机后系统会清掉所有闹钟，这里按上次记下的时刻重新排一次 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val store = ReminderStore(app)
        if (!store.load().enabled) return
        val at = store.nextAlarmAt()
        if (at <= 0L) return
        ReminderScheduler.schedule(app, (at - System.currentTimeMillis()).coerceAtLeast(1_000L))
    }
}

/**
 * 后台的一次完整检查。失败时**不发任何通知**，只记下原因并（在还有希望时）再排一次。
 *
 * 理由：`oauthCode` 过期后，我们手上的体力预测可能早就因为用户花体力而作废，
 * 拿它去发「已满」是误报。宁可漏一条，不可乱弹。
 */
internal suspend fun evaluate(context: Context) {
    val store = ReminderStore(context)
    val state = store.load()
    if (!state.enabled) {
        ReminderScheduler.cancel(context)
        return
    }

    val oauthCode = CredentialStore(context).loadOauthCode()
    if (oauthCode.isNullOrBlank()) {
        skipThisRound(context, store, "没有 oauthCode，无法后台查询")
        return
    }

    val result = runCatching {
        withTimeout(RECEIVER_FETCH_TIMEOUT_MS) { LauncherApi.fetchAccount(oauthCode) }
    }.getOrNull()

    if (result is ApiResult.Success) {
        store.setLastSkipReason("")
        applyFetchedAccount(context, store, result)
        return
    }

    val reason = when (result) {
        ApiResult.TokenExpired -> "oauthCode 已失效，请重新推送凭证"
        is ApiResult.Failure -> "后台查询失败：${result.message}"
        else -> "后台查询超时"
    }
    skipThisRound(context, store, reason)
}

private fun skipThisRound(context: Context, store: ReminderStore, reason: String) {
    store.setLastSkipReason(reason)
    val delay = decideOffline(store.load(), System.currentTimeMillis(), store.lastClockOffset())
    if (delay != null) {
        ReminderScheduler.schedule(context, delay)
    } else {
        ReminderScheduler.cancel(context)
    }
}

/**
 * 拿到一次成功的账号数据之后要做的全部事情。
 *
 * 主界面的刷新也走这里 —— 每次成功取数都会重排整条链，这样「体力满着时链条停住」
 * 之后，只要你打开一次 App 就能重新武装。
 */
internal fun applyFetchedAccount(
    context: Context,
    store: ReminderStore,
    success: ApiResult.Success,
    allowNotify: Boolean = true,
) {
    val account = success.data
    store.setLastClockOffset(success.clockOffsetMillis)

    val state = beginCycle(store.load(), account.energyRecoverTime)
    val input = ReminderInput(
        deviceNow = System.currentTimeMillis(),
        clockOffset = success.clockOffsetMillis,
        energy = account.energy,
        maxEnergy = account.maxEnergy,
        fullAtServer = account.energyRecoverTime,
    )

    when (val decision = decide(state, input)) {
        is ReminderDecision.Rearm -> {
            store.save(state)
            ReminderScheduler.schedule(context, decision.delayMs)
        }

        is ReminderDecision.Publish -> {
            // 刚打开开关时不要补发历史通知（比如「体力已满」），但接下来该提醒的照常排
            val posted = if (!allowNotify) {
                true
            } else when (val kind = decision.kind) {
                is ReminderKind.Soon -> {
                    val remaining = input.fullAtServer - (input.deviceNow + input.clockOffset)
                    StaminaNotifier.notifySoon(context, minutesRoundedUp(remaining), kind.index)
                }
                ReminderKind.Full -> StaminaNotifier.notifyFull(context)
            }
            store.save(
                when (val kind = decision.kind) {
                    is ReminderKind.Soon -> state.withFiredLead(kind.index)
                    ReminderKind.Full -> state.copy(firedFull = true)
                },
            )
            if (allowNotify && !posted) {
                store.setLastSkipReason("通知权限未授予，通知没有发出去")
            }
            decision.nextDelayMs
                ?.let { ReminderScheduler.schedule(context, it) }
                ?: ReminderScheduler.cancel(context)
        }

        ReminderDecision.Idle, ReminderDecision.Done -> {
            store.save(state)
            ReminderScheduler.cancel(context)
        }
    }
}

/** 毫秒向上取整成分钟，至少 1 —— 「还有 0 分钟」很怪 */
private fun minutesRoundedUp(millis: Long): Long =
    ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)

private const val RECEIVER_FETCH_TIMEOUT_MS = 8_000L
