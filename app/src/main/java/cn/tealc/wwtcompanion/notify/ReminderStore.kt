package cn.tealc.wwtcompanion.notify

import android.content.Context

/**
 * 提醒状态与设置的持久化。
 *
 * 单独用一个 SharedPreferences 文件（`wwt_reminder`），不和应用凭证那个
 * `wwt_companion` 混在一起 —— 两者生命周期不同，清凭证不该顺带清掉提醒设置。
 */
class ReminderStore(context: Context) {

    private val prefs = context.getSharedPreferences("wwt_reminder", Context.MODE_PRIVATE)

    fun load(): ReminderState = ReminderState(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        cycleFullAt = prefs.getLong(KEY_CYCLE, 0L),
        firedLeadMask = prefs.getInt(KEY_LEAD_MASK, 0),
        firedFull = prefs.getBoolean(KEY_FIRED_FULL, false),
    )

    fun save(state: ReminderState) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putLong(KEY_CYCLE, state.cycleFullAt)
            .putInt(KEY_LEAD_MASK, state.firedLeadMask)
            .putBoolean(KEY_FIRED_FULL, state.firedFull)
            .apply()
    }

    /** 下次唤醒的本机时刻，0 = 没有排期。仅用于设置页展示，让「链条还活着吗」看得见。 */
    fun nextAlarmAt(): Long = prefs.getLong(KEY_NEXT_AT, 0L)

    fun setNextAlarmAt(value: Long) {
        prefs.edit().putLong(KEY_NEXT_AT, value).apply()
    }

    /** 上次没有排期的原因（例如凭证失效），空串表示一切正常 */
    fun lastSkipReason(): String = prefs.getString(KEY_SKIP, "").orEmpty()

    fun setLastSkipReason(value: String) {
        prefs.edit().putString(KEY_SKIP, value).apply()
    }

    /** 最近一次成功取数时的时钟偏移，后台无网时用它换算 */
    fun lastClockOffset(): Long = prefs.getLong(KEY_CLOCK, 0L)

    fun setLastClockOffset(value: Long) {
        prefs.edit().putLong(KEY_CLOCK, value).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_CYCLE = "cycle_full_at"
        const val KEY_LEAD_MASK = "fired_lead_mask"
        const val KEY_FIRED_FULL = "fired_full"
        const val KEY_NEXT_AT = "next_alarm_at"
        const val KEY_SKIP = "last_skip_reason"
        const val KEY_CLOCK = "last_clock_offset"
    }
}
