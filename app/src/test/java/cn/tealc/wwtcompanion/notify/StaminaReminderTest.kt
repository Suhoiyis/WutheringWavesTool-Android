package cn.tealc.wwtcompanion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaReminderTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val lead = 30 * minute

    private fun input(
        remainingMillis: Long,
        energy: Int = 100,
        maxEnergy: Int = 240,
        clockOffset: Long = 0L,
        leadMillis: Long = lead,
        deviceNow: Long = now,
    ) = ReminderInput(
        deviceNow = deviceNow,
        clockOffset = clockOffset,
        energy = energy,
        maxEnergy = maxEnergy,
        fullAtServer = if (remainingMillis <= 0L) 0L else deviceNow + clockOffset + remainingMillis,
        leadMillis = leadMillis,
    )

    private fun on() = ReminderState(enabled = true)

    @Test
    fun `离回满还早时只排到提前量的位置`() {
        val d = decide(on(), input(remainingMillis = 180 * minute))
        assertEquals(ReminderDecision.Rearm(150 * minute), d)
    }

    @Test
    fun `进入提前窗口就发快满通知并接着排到回满时刻`() {
        val d = decide(on(), input(remainingMillis = 20 * minute))
        assertEquals(ReminderDecision.Publish(ReminderKind.SOON, 20 * minute), d)
    }

    @Test
    fun `快满已经提醒过就不再重复发`() {
        val state = on().copy(firedSoon = true)
        val d = decide(state, input(remainingMillis = 20 * minute))
        assertEquals(ReminderDecision.Rearm(20 * minute), d)
    }

    @Test
    fun `体力顶格时发已满通知且之后不再排期`() {
        val d = decide(on(), input(remainingMillis = 0L, energy = 240))
        assertEquals(ReminderDecision.Publish(ReminderKind.FULL, null), d)
    }

    @Test
    fun `已满且提醒过就彻底结束这一轮`() {
        val state = on().copy(firedFull = true)
        assertEquals(ReminderDecision.Done, decide(state, input(remainingMillis = 0L, energy = 240)))
    }

    @Test
    fun `接口给的回满时间是 0 说明已满`() {
        val d = decide(on(), input(remainingMillis = 0L, energy = 10))
        assertEquals(ReminderDecision.Publish(ReminderKind.FULL, null), d)
    }

    @Test
    fun `本机时钟比服务器快一小时也不会误判成已满`() {
        // 服务器比本机慢 1 小时。真实还剩 10 分钟。
        val offset = -60 * minute
        val d = decide(on(), input(remainingMillis = 10 * minute, clockOffset = offset))
        assertEquals(ReminderDecision.Publish(ReminderKind.SOON, 10 * minute), d)
    }

    @Test
    fun `回满时间变了算新一轮，已发标记要清空`() {
        val old = on().copy(cycleFullAt = now + 60 * minute, firedSoon = true, firedFull = true)
        val next = beginCycle(old, now + 90 * minute)
        assertEquals(now + 90 * minute, next.cycleFullAt)
        assertTrue(!next.firedSoon && !next.firedFull)
    }

    @Test
    fun `回满时间没变就保留已发标记`() {
        val old = on().copy(cycleFullAt = now + 60 * minute, firedSoon = true)
        val next = beginCycle(old, now + 60 * minute)
        assertTrue(next.firedSoon)
    }

    @Test
    fun `已满时回满时间是 0，不能当成新一轮反复提醒`() {
        val old = on().copy(cycleFullAt = now + 60 * minute, firedFull = true)
        val next = beginCycle(old, 0L)
        assertTrue(next.firedFull)
        assertEquals(now + 60 * minute, next.cycleFullAt)
    }

    @Test
    fun `花掉体力后回满时间推后，提醒会重新排到更晚`() {
        // 用户把体力花到只剩 40 点，从此刻起要 150 分钟才满
        val d = decide(on(), input(remainingMillis = 150 * minute, energy = 40))
        assertEquals(ReminderDecision.Rearm(120 * minute), d)
    }

    @Test
    fun `开关关着就什么都不做`() {
        assertEquals(
            ReminderDecision.Idle,
            decide(ReminderState(enabled = false), input(remainingMillis = 5 * minute)),
        )
    }

    @Test
    fun `后台刷新失败时只重排不发通知`() {
        val state = on().copy(cycleFullAt = now + 40 * minute)
        assertEquals(40 * minute, decideOffline(state, now, 0L))
    }

    @Test
    fun `后台刷新失败且已过回满时刻就停下，不凭过时预测发通知`() {
        val state = on().copy(cycleFullAt = now - 1L)
        assertNull(decideOffline(state, now, 0L))
    }
}
