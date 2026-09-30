package cn.tealc.wwtcompanion.gacha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽卡统计的单元测试。
 *
 * 用到的五星 ID：
 *  - 1104 / 1301 / 1405 / 1503 —— 常驻（歪）
 *  - 1108 / 9999 —— 限定（UP）
 */
class GachaStatsTest {

    // ------------------------------------------------------------ 造数据的小工具

    /**
     * 造一组同卡池的记录，**索引 0 是最新的**（和接口返回顺序一致）。
     * 时间按索引递减，保证「顺序」和「时间」不打架。
     */
    private fun make(pool: GachaPool, vararg specs: Pair<Int, Int>): List<GachaRecord> {
        val n = specs.size
        return specs.mapIndexed { i, (id, quality) ->
            GachaRecord(
                poolId = pool.id,
                cardPoolType = "调谐",
                resourceId = id,
                qualityLevel = quality,
                resourceType = if (pool.category == GachaPool.Category.WEAPON) "武器" else "角色",
                name = "物品$id",
                count = 1,
                time = "2026-01-01 00:00:%02d".format(n - i),
            )
        }
    }

    private fun rec(
        pool: GachaPool,
        id: Int,
        quality: Int,
        time: String,
        name: String = "物品$id",
    ) = GachaRecord(pool.id, "调谐", id, quality, "角色", name, 1, time)

    // ------------------------------------------------------------------ 基础汇总

    @Test
    fun `没有数据时全部为零且不崩`() {
        val s = GachaStatsCalculator.compute(emptyList())
        assertTrue(s.isEmpty)
        assertEquals(0, s.totalPulls)
        assertEquals(0, s.ssrCount)
        assertEquals(0.0, s.ssrAverage, 0.0001)
        assertNull(s.nonBannerRate)
        assertNull(s.topSsr)
        assertEquals(13, s.pools.size) // 13 个卡池槽位始终存在
        assertTrue(s.pools.none { it.hasData })
    }

    @Test
    fun `总抽数与星声按一抽160折算`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 5, 2 to 4, 3 to 3)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(3, s.totalPulls)
        assertEquals(3 * 160, s.totalAstrites)
        assertEquals(1, s.ssrCount)
        assertEquals(1, s.srCount)
        assertEquals(1, s.rCount)
    }

    @Test
    fun `五星平均等于总抽数除以五星数`() {
        // 4 抽里 1 个五星
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 5, 2 to 3, 3 to 3, 4 to 3)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(4.0, s.ssrAverage, 0.0001)
    }

    // -------------------------------------------------------------------- 已垫

    @Test
    fun `没有五星时已垫等于总抽数`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 4, 2 to 3, 3 to 3)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(3, pool.pitySsr)
    }

    @Test
    fun `有五星时已垫等于距最近五星的抽数`() {
        // 索引 0、1 是三星，索引 2 是五星 → 已垫 2
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 3, 2 to 3, 3 to 5, 4 to 3)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(2, pool.pitySsr)
    }

    @Test
    fun `四星已垫会被最近的五星截断`() {
        // 索引 0、1 三星；2 五星；5 四星（比五星老，应该被重置掉）
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 3, 2 to 3, 3 to 5, 4 to 3, 5 to 3, 6 to 4)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(2, pool.pitySsr)
        assertEquals("四星计数被五星重置", 2, pool.pitySr)
    }

    // ---------------------------------------------------------------- 50/50

    @Test
    fun `连续两次限定五星算两次全赢`() {
        // 时间从旧到新：1108（限定）、9999（限定）
        val rs = make(GachaPool.CHARACTER_EVENT, 9999 to 5, 1108 to 5)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(2, s.nonBannerTotal)
        assertEquals(2, s.nonBannerWins)
        assertEquals(1.0, s.nonBannerRate!!, 0.0001)
    }

    @Test
    fun `歪了之后的下一个五星是大保底不计入分母`() {
        // 时间从旧到新：1104（常驻=歪）、1108（限定，但属于大保底）
        val rs = make(GachaPool.CHARACTER_EVENT, 1108 to 5, 1104 to 5)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals("大保底不算一次 50/50", 1, s.nonBannerTotal)
        assertEquals("那次是歪，所以 0 胜", 0, s.nonBannerWins)
        assertEquals(0.0, s.nonBannerRate!!, 0.0001)
    }

    @Test
    fun `歪一次赢一次再歪一次`() {
        // 旧→新：1104(歪, 下次保底) 1108(保底,不计) 1104(歪, 下次保底)
        val rs = make(GachaPool.CHARACTER_EVENT, 1104 to 5, 1108 to 5, 1104 to 5)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(2, s.nonBannerTotal)
        assertEquals(0, s.nonBannerWins)
    }

    @Test
    fun `常驻池和武器池不参与不歪率统计`() {
        val rs = make(GachaPool.CHARACTER_STANDARD, 9999 to 5) +
            make(GachaPool.WEAPON_EVENT, 9999 to 5)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(0, s.nonBannerTotal)
        assertNull(s.nonBannerRate)
    }

    @Test
    fun `保底状态在卡池之间独立不共享`() {
        // 角色活动池：歪了（留下大保底状态）
        // 角色新旅池：限定 —— 如果状态共享就会被当成大保底不计入，独立则应计入且算赢
        val rs = make(GachaPool.CHARACTER_EVENT, 1104 to 5) +
            make(GachaPool.CHARACTER_NEW, 1108 to 5)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(2, s.nonBannerTotal)
        assertEquals(1, s.nonBannerWins)
    }

    // -------------------------------------------------------------- 角色/武器

    @Test
    fun `角色与武器按卡池编号分类而不是中文名`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 3, 2 to 3) +
            make(GachaPool.WEAPON_EVENT, 3 to 3)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(2, s.rolePulls)
        assertEquals(1, s.weaponPulls)
    }

    @Test
    fun `新手池计入角色侧`() {
        // 桌面版靠中文前缀判断，新手池两边都不算；这里是有意修正
        val rs = make(GachaPool.NOVICE, 1503 to 5, 1 to 3)
        val s = GachaStatsCalculator.compute(rs)
        assertEquals(2, s.rolePulls)
        assertEquals(1, s.roleSsr)
        assertEquals(0, s.weaponPulls)
    }

    // ---------------------------------------------------------------- 最欧最非

    @Test
    fun `最欧最非来自每张五星的消耗段`() {
        // 索引:  0     1  2     3     4  5  6  7     8     9 10 11
        // 星级:  5★    3  3     5★    3  3  3  3     5★    3  3  3
        // 段长:  └── 3 ──┘     └──── 5 ────┘       └── 4 ──┘
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            100 to 5,
            1 to 3, 2 to 3,
            200 to 5,
            3 to 3, 4 to 3, 5 to 3, 6 to 3,
            300 to 5,
            7 to 3, 8 to 3, 9 to 3,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(3, pool.ssrCount)
        assertEquals("最新一发就是五星，所以已垫为 0", 0, pool.pitySsr)
        assertEquals("最欧 = 3", 3, pool.ssrBest)
        assertEquals("最非 = 5", 5, pool.ssrWorst)
        assertEquals(4.0, pool.ssrAverage, 0.0001)
    }

    @Test
    fun `五星平均不把还在垫的抽数算进分母`() {
        // 最近 4 抽没出金（已垫 4），唯一的五星在最老端，它那一段只有 1 抽。
        // 拿总抽数 5 ÷ 五星数 1 会得出 5.0 —— 比同一张卡上的「最非 1」还大，
        // 自相矛盾。桌面版口径是「已完成的段求和 ÷ 五星数」= 1.0。
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            1 to 3, 2 to 3, 3 to 3, 4 to 3,
            100 to 5,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals("已垫 = 4", 4, pool.pitySsr)
        assertEquals("最非 = 1", 1, pool.ssrWorst)
        assertEquals("平均必须和最欧最非同源", 1.0, pool.ssrAverage, 0.0001)
    }

    @Test
    fun `最老那一段照样计入统计`() {
        // 「剔首金」功能已删除：最老那一张五星的段长起点不明、会虚高，
        // 但它确实是抽出来的，就按原样计入，不做特殊处理。
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            100 to 5, 1 to 3, 2 to 3,
            200 to 5, 3 to 3, 4 to 3, 5 to 3, 6 to 3,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(2, pool.ssrCount)
        assertEquals("最老那张的消耗段（8-3）", 5, pool.ssrWorst)
        assertEquals(8, GachaStatsCalculator.compute(rs).totalPulls)
    }

    // ------------------------------------------------------------------ 排行

    @Test
    fun `排行按抽数倒序`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 3, 1 to 3, 1 to 3, 2 to 3, 2 to 3, 3 to 4)
        val rank = GachaStatsCalculator.rank(rs)
        assertEquals(listOf(1, 2, 3), rank.map { it.resourceId })
        assertEquals(listOf(3, 2, 1), rank.map { it.count })
    }

    @Test
    fun `排行可以按分类和星级筛选`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 3, 5 to 4) +
            make(GachaPool.WEAPON_EVENT, 2 to 3)
        assertEquals(2, GachaStatsCalculator.rank(rs, GachaPool.Category.CHARACTER).size)
        assertEquals(1, GachaStatsCalculator.rank(rs, GachaPool.Category.WEAPON).size)
        assertEquals(1, GachaStatsCalculator.rank(rs, qualityLevel = 4).size)
        assertEquals(1, GachaStatsCalculator.rank(rs, GachaPool.Category.CHARACTER, 4).size)
    }

    @Test
    fun `TOP五星取出现次数最多的那个`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1108 to 5, 1108 to 5, 1301 to 5)
        val s = GachaStatsCalculator.compute(rs)
        val top = s.topSsr
        assertTrue(top != null)
        assertEquals("物品1108", top!!.name)
        assertEquals(2, top.count)
        assertEquals("限定五星里也只有它", "物品1108", s.topUpSsr!!.name)
    }

    // ------------------------------------------------------- 每个卡池独立统计

    @Test
    fun `不歪率按卡池分别统计互不影响`() {
        // 角色活动池：歪一次 → 0/1
        // 角色新旅池：直接限定 → 1/1
        val rs = make(GachaPool.CHARACTER_EVENT, 1104 to 5, 1 to 3) +
            make(GachaPool.CHARACTER_NEW, 1108 to 5, 2 to 3)
        val s = GachaStatsCalculator.compute(rs)
        val event = s.pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        val new = s.pools.first { it.pool == GachaPool.CHARACTER_NEW }

        assertEquals(0, event.nonBannerWins)
        assertEquals(1, event.nonBannerTotal)
        assertEquals(0.0, event.nonBannerRate!!, 0.0001)

        assertEquals(1, new.nonBannerWins)
        assertEquals(1, new.nonBannerTotal)
        assertEquals(1.0, new.nonBannerRate!!, 0.0001)

        // 汇总仍然是两者相加
        assertEquals(1, s.nonBannerWins)
        assertEquals(2, s.nonBannerTotal)
    }

    @Test
    fun `没有50-50机制的卡池不歪率为空`() {
        val rs = make(GachaPool.CHARACTER_STANDARD, 1104 to 5) +
            make(GachaPool.WEAPON_EVENT, 21020086 to 5)
        val s = GachaStatsCalculator.compute(rs)
        s.pools.forEach { assertNull("${it.pool.displayName} 不该有不歪率", it.nonBannerRate) }
        assertEquals(0, s.nonBannerTotal)
    }

    @Test
    fun `UP平均只看限定五星之间的距离`() {
        // 索引  0     1..3   4     5..9   10    11..14
        // 星级  5★UP  3★     5★常驻 3★     5★UP  3★
        // 段长  └──── 10 ────┘            └─ 5 ─┘
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            1108 to 5,
            1 to 3, 2 to 3, 3 to 3,
            1104 to 5,
            4 to 3, 5 to 3, 6 to 3, 7 to 3, 8 to 3,
            9999 to 5,
            9 to 3, 10 to 3, 11 to 3, 12 to 3,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(3, pool.ssrCount)
        assertEquals(2, pool.upSsrCount)
        assertEquals("UP 段是 10 和 5", 7.5, pool.upAverage, 0.0001)
        assertEquals("五星段是 4 / 6 / 5", 6, pool.ssrWorst)
        assertEquals(4, pool.ssrBest)
    }

    @Test
    fun `只有一个限定五星时UP平均等于它之前的全部抽数`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1108 to 5, 1 to 3, 2 to 3, 3 to 3)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(1, pool.upSsrCount)
        assertEquals(4.0, pool.upAverage, 0.0001)
    }

    @Test
    fun `稀有度占比按本池抽数计算`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1 to 5, 2 to 4, 3 to 3, 4 to 3)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(0.25, pool.share(pool.ssrCount), 0.0001)
        assertEquals(0.25, pool.share(pool.srCount), 0.0001)
        assertEquals(0.5, pool.share(pool.rCount), 0.0001)
    }

    @Test
    fun `没有数据的卡池各项都为零`() {
        val rs = make(GachaPool.CHARACTER_EVENT, 1108 to 5)
        val empty = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.WEAPON_EVENT }
        assertTrue(!empty.hasData)
        assertEquals(0, empty.total)
        assertEquals(0, empty.upSsrCount)
        assertEquals(0.0, empty.upAverage, 0.0001)
        assertEquals(0.0, empty.share(0), 0.0001)
    }

    @Test
    fun `同一秒内出两个五星时按真实先后算不歪`() {
        // 时间只精确到秒：一次十连里出了两个五星，两条记录 time 完全相同。
        // 列表是时间倒序，所以「下标大的更早」。这里 1104（常驻）在下标 0（更新），
        // 1108（限定）在下标 1（更早）→ 真实顺序是先出限定、再出常驻。
        // 于是限定赢了自己的 50/50，常驻则输掉一次 → 1 胜 / 2 次。
        // 如果实现里先按时间稳定排序再遍历，同一秒的顺序会被翻过来，算成 0/1。
        val sameSecond = "2026-01-01 00:00:09"
        val rs = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1104, 5, sameSecond), // 常驻，较新
            rec(GachaPool.CHARACTER_EVENT, 1108, 5, sameSecond), // 限定，更早
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals("先出的限定赢了 50/50", 1, pool.nonBannerWins)
        assertEquals("常驻那一次是第二次小保底", 2, pool.nonBannerTotal)
        assertEquals(0.5, pool.nonBannerRate!!, 0.0001)
    }

    // ------------------------------------------------- 每张五星的消耗抽数明细

    @Test
    fun `每张五星都带着自己的消耗抽数`() {
        // 索引  0      1..2    3      4..6    7
        // 星级  5★UP   3★      5★常驻  3★      5★UP(最老)
        // 段长  └── 3 ──┘      └── 4 ──┘       └ 8-7=1 ┘
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            1108 to 5,
            1 to 3, 2 to 3,
            1301 to 5,
            3 to 3, 4 to 3, 5 to 3,
            9999 to 5,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(3, pool.fiveStarPulls.size)

        // 列表是时间倒序：最新的排第一
        assertEquals(listOf(1108, 1301, 9999), pool.fiveStarPulls.map { it.resourceId })
        assertEquals("消耗抽数", listOf(3, 4, 1), pool.fiveStarPulls.map { it.pulls })

        // UP 标记：1108、9999 是限定，1301 是常驻（歪）
        assertEquals(listOf(true, false, true), pool.fiveStarPulls.map { it.isUp })
    }

    @Test
    fun `五星明细里的消耗抽数与最欧最非一致`() {
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            100 to 5, 1 to 3, 2 to 3,
            200 to 5, 3 to 3, 4 to 3, 5 to 3, 6 to 3,
            300 to 5, 7 to 3,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        val pulls = pool.fiveStarPulls.map { it.pulls }
        assertEquals(pool.ssrBest, pulls.min())
        assertEquals(pool.ssrWorst, pulls.max())
    }

    @Test
    fun `UP占比是构成比而不是胜率`() {
        // 3 张五星里 2 张限定：UP 占比 2/3，但 50/50 是另一回事
        val rs = make(
            GachaPool.CHARACTER_EVENT,
            9999 to 5,
            1108 to 5,
            1301 to 5,
        )
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_EVENT }
        assertEquals(3, pool.ssrCount)
        assertEquals(2, pool.upSsrCount)
        assertEquals(2.0 / 3.0, pool.upRate, 0.0001)
        // 时间顺序：1301(歪) → 1108(大保底,不计) → 9999(赢) ⇒ 1/2
        assertEquals(1, pool.nonBannerWins)
        assertEquals(2, pool.nonBannerTotal)
    }

    @Test
    fun `没有五星时明细为空且UP占比为零`() {
        val rs = make(GachaPool.CHARACTER_STANDARD, 1 to 3, 2 to 4)
        val pool = GachaStatsCalculator.compute(rs).pools.first { it.pool == GachaPool.CHARACTER_STANDARD }
        assertTrue(pool.fiveStarPulls.isEmpty())
        assertEquals(0.0, pool.upRate, 0.0001)
    }

    // ------------------------------------------------------------------ 合并

    @Test
    fun `新数据为空时原样保留历史`() {
        val old = listOf(rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:01"))
        assertEquals(old, GachaStatsCalculator.merge(old, emptyList()))
    }

    @Test
    fun `旧数据为空时直接采用新数据`() {
        val new = listOf(rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:01"))
        assertEquals(new, GachaStatsCalculator.merge(emptyList(), new))
    }

    @Test
    fun `比新数据更老的历史会被保留`() {
        val old = listOf(
            rec(GachaPool.CHARACTER_EVENT, 9, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:01"),
        )
        val new = listOf(rec(GachaPool.CHARACTER_EVENT, 9, 3, "2026-01-01 00:00:09"))
        val merged = GachaStatsCalculator.merge(old, new)
        assertEquals(2, merged.size)
        assertEquals(listOf(9, 1), merged.map { it.resourceId })
    }

    @Test
    fun `同一时刻的重复记录不会被去重丢掉`() {
        // 同一次十连里抽到两把相同的三星武器，时间戳完全一样 —— 这是正常数据
        val old = listOf(
            rec(GachaPool.CHARACTER_EVENT, 7, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 7, 3, "2026-01-01 00:00:09"),
        )
        val merged = GachaStatsCalculator.merge(old, emptyList())
        assertEquals(2, merged.size)
    }

    @Test
    fun `导入更旧的存档不会抹掉本地更新的记录`() {
        // 「导入」按钮选错文件、又导入了一次几个月前的 pool.json 时的情形。
        // 之前只补「比边界更老」的那一段，本地更新的记录会被整段抹掉。
        val local = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-02-01 00:00:03"),
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-02-01 00:00:02"),
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-02-01 00:00:01"),
        )
        val older = listOf(
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:02"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:01"),
        )
        val merged = GachaStatsCalculator.merge(local, older)
        assertEquals("2 月的记录不能被 1 月的旧存档冲掉", 5, merged.size)
        assertEquals(
            "整体仍是时间倒序",
            listOf(
                "2026-02-01 00:00:03", "2026-02-01 00:00:02", "2026-02-01 00:00:01",
                "2026-01-01 00:00:02", "2026-01-01 00:00:01",
            ),
            merged.map { it.time },
        )
    }

    @Test
    fun `部分重叠时本地比边界新的记录也会保留`() {
        // 接口这次只回溯到 01:07，本地 01:10 那条不在返回里，必须留住
        val local = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:10"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 3, 3, "2026-01-01 00:00:08"),
        )
        val new = listOf(
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 3, 3, "2026-01-01 00:00:08"),
            rec(GachaPool.CHARACTER_EVENT, 4, 3, "2026-01-01 00:00:07"),
        )
        val merged = GachaStatsCalculator.merge(local, new)
        assertEquals(4, merged.size)
        assertEquals(
            listOf("2026-01-01 00:00:10", "2026-01-01 00:00:09",
                "2026-01-01 00:00:08", "2026-01-01 00:00:07"),
            merged.map { it.time },
        )
        assertEquals("没有重复", 4, merged.map { it.resourceId }.toSet().size)
    }

    @Test
    fun `分页边界上没抓全的记录会被补回来`() {
        // 十连共 3 条挤在 00:00:09，这次接口只返回了其中 2 条
        val old = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 3, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 4, 3, "2026-01-01 00:00:08"),
        )
        val new = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:09"),
        )
        val merged = GachaStatsCalculator.merge(old, new)
        assertEquals(4, merged.size)
        assertEquals(setOf(1, 2, 3, 4), merged.map { it.resourceId }.toSet())
    }

    @Test
    fun `完整重抓不会产生重复`() {
        val old = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 3, 3, "2026-01-01 00:00:08"),
        )
        val new = old.toList()
        assertEquals(3, GachaStatsCalculator.merge(old, new).size)
    }

    @Test
    fun `合并是幂等的`() {
        val old = listOf(
            rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:09"),
            rec(GachaPool.CHARACTER_EVENT, 2, 3, "2026-01-01 00:00:02"),
        )
        val new = listOf(rec(GachaPool.CHARACTER_EVENT, 1, 3, "2026-01-01 00:00:09"))
        val once = GachaStatsCalculator.merge(old, new)
        val twice = GachaStatsCalculator.merge(once, new)
        assertEquals(once, twice)
    }

    @Test
    fun `不同卡池之间互不干扰`() {
        val old = listOf(rec(GachaPool.WEAPON_EVENT, 5, 3, "2026-01-01 00:00:01"))
        val new = listOf(rec(GachaPool.CHARACTER_EVENT, 6, 3, "2026-01-01 00:00:09"))
        val merged = GachaStatsCalculator.merge(old, new)
        assertEquals(2, merged.size)
        assertEquals(setOf(GachaPool.WEAPON_EVENT.id, GachaPool.CHARACTER_EVENT.id), merged.map { it.poolId }.toSet())
    }

    // ---------------------------------------------------------------- 卡池表

    @Test
    fun `卡池编号与中文名一一对应`() {
        assertEquals(13, GachaPool.entries.size)
        assertEquals(GachaPool.CHARACTER_EVENT, GachaPool.byId(1))
        assertEquals(GachaPool.WEAPON_MEMORY, GachaPool.byId(13))
        assertNull(GachaPool.byId(14))
        assertEquals(GachaPool.NOVICE, GachaPool.byDisplayName("新手唤取"))
        assertNull(GachaPool.byDisplayName("不存在的池"))
        GachaPool.entries.forEach { assertEquals(it, GachaPool.byId(it.id)) }
    }
}
