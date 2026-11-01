package cn.tealc.wwtcompanion.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaminaReminderTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun input(
        remainingMillis: Long,
        energy: Int = 100,
        maxEnergy: Int = 240,
        clockOffset: Long = 0L,
        deviceNow: Long = now,
        leads: List<Long> = REMINDER_LEADS_MILLIS,
    ) = ReminderInput(
        deviceNow = deviceNow,
        clockOffset = clockOffset,
        energy = energy,
        maxEnergy = maxEnergy,
        fullAtServer = if (remainingMillis <= 0L) 0L else deviceNow + clockOffset + remainingMillis,
        leadsMillis = leads,
    )

    private fun on() = ReminderState(enabled = true)

    // —— 节点推进 ——

    @Test
    fun `离回满还早时排到最宽的那个节点`() {
        // 还剩 90 分钟，最早的通知节点是 60 分钟，所以 30 分钟后再醒
        assertEquals(
            ReminderDecision.Rearm(30 * minute),
            decide(on(), input(remainingMillis = 90 * minute)),
        )
    }

    @Test
    fun `进入六十分钟窗口先提醒一次，再排到三十分钟节点`() {
        // 还剩 50 分钟：已在 60 分钟窗口内，下一次唤醒是三十分钟节点（20 分钟后）
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Soon(index = 0, leadMinutes = 60), 20 * minute),
            decide(on(), input(remainingMillis = 50 * minute)),
        )
    }

    @Test
    fun `进入三十分钟窗口提醒一次，再排到回满`() {
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Soon(index = 1, leadMinutes = 30), 20 * minute),
            decide(on(), input(remainingMillis = 20 * minute)),
        )
    }

    @Test
    fun `六十已经提醒过就不再重复，但仍会排到三十分钟节点`() {
        val state = on().withFiredLead(0)
        assertEquals(
            ReminderDecision.Rearm(20 * minute),
            decide(state, input(remainingMillis = 50 * minute)),
        )
    }

    @Test
    fun `三十分钟已经提醒过就静默等到回满`() {
        val state = on().withFiredLead(1)
        assertEquals(
            ReminderDecision.Rearm(20 * minute),
            decide(state, input(remainingMillis = 20 * minute)),
        )
    }

    @Test
    fun `两个节点都提醒过仍然会等到回满再发已满`() {
        val state = on().withFiredLead(0).withFiredLead(1)
        assertEquals(
            ReminderDecision.Rearm(5 * minute),
            decide(state, input(remainingMillis = 5 * minute)),
        )
    }

    @Test
    fun `节点列表可以随便加，判定逻辑不用改`() {
        // 加一个 120 分钟的节点，逻辑照样成立
        val leads = listOf(120 * minute, 60 * minute, 30 * minute)
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Soon(index = 0, leadMinutes = 120), 40 * minute),
            decide(on(), input(remainingMillis = 100 * minute, leads = leads)),
        )
    }

    // —— 已满 ——

    @Test
    fun `体力顶格时发已满通知且之后不再排期`() {
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Full, null),
            decide(on(), input(remainingMillis = 0L, energy = 240)),
        )
    }

    @Test
    fun `已满且提醒过就彻底结束这一轮`() {
        val state = on().copy(firedFull = true)
        assertEquals(ReminderDecision.Done, decide(state, input(remainingMillis = 0L, energy = 240)))
    }

    @Test
    fun `接口给的回满时间是 0 说明已满`() {
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Full, null),
            decide(on(), input(remainingMillis = 0L, energy = 10)),
        )
    }

    // —— 周期与时钟 ——

    @Test
    fun `本机时钟比服务器快一小时也不会误判成已满`() {
        val offset = -60 * minute
        assertEquals(
            ReminderDecision.Publish(ReminderKind.Soon(index = 1, leadMinutes = 30), 10 * minute),
            decide(on(), input(remainingMillis = 10 * minute, clockOffset = offset)),
        )
    }

    @Test
    fun `回满时间变了算新一轮，已发标记要清空`() {
        val old = on()
            .copy(cycleFullAt = now + 60 * minute, firedLeadMask = 0b11, firedFull = true)
        val next = beginCycle(old, now + 90 * minute)
        assertEquals(now + 90 * minute, next.cycleFullAt)
        assertEquals(0, next.firedLeadMask)
        assertTrue(!next.firedFull)
    }

    @Test
    fun `回满时间没变就保留已发标记`() {
        val old = on().copy(cycleFullAt = now + 60 * minute, firedLeadMask = 0b01)
        assertEquals(0b01, beginCycle(old, now + 60 * minute).firedLeadMask)
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
        // 只剩 40 点，从此刻起 150 分钟才满 → 排到 60 分钟节点（90 分钟后）
        assertEquals(
            ReminderDecision.Rearm(90 * minute),
            decide(on(), input(remainingMillis = 150 * minute, energy = 40)),
        )
    }

    // —— 边界 ——

    @Test
    fun `开关关着就什么都不做`() {
        assertEquals(
            ReminderDecision.Idle,
            decide(ReminderState(enabled = false), input(remainingMillis = 5 * minute)),
        )
    }

    @Test
    fun `节点列表为空时退化成只等回满`() {
        assertEquals(
            ReminderDecision.Rearm(50 * minute),
            decide(on(), input(remainingMillis = 50 * minute, leads = emptyList())),
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
