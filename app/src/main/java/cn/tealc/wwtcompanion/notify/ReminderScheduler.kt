package cn.tealc.wwtcompanion.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 用 [AlarmManager] 给「下一次该检查的时刻」排一个唤醒。
 *
 * 这里用的是 `setAndAllowWhileIdle`（**非精确**闹钟）：
 * - 不需要 `SCHEDULE_EXACT_ALARM` 权限（Android 14 起该权限默认拒绝，要用户手动去
 *   「闹钟和提醒」里开，且 Google 的政策只该留给真正的闹钟/日历类应用）；
 * - Doze 里依然会被放行，只是允许的频率有限，可能晚几分钟。
 *
 * 对「还有半小时满」这种提醒来说，晚几分钟无所谓，换来的是零权限负担。
 */
object ReminderScheduler {

    private const val REQUEST_CODE = 1001

    /** 把下一次检查排到 [delayMillis] 之后 */
    fun schedule(context: Context, delayMillis: Long) {
        // 0 或负数会被系统当成「立即」，这里留一秒缓冲避免和当前时刻打架
        val delay = delayMillis.coerceAtLeast(1_000L)
        val at = System.currentTimeMillis() + delay
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, 0))
        ReminderStore(context).setNextAlarmAt(at)
    }

    /** 取消排期（关闭提醒时用） */
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context, 0))
        ReminderStore(context).setNextAlarmAt(0L)
    }

    private fun pendingIntent(context: Context, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java),
            flags or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
