package cn.tealc.wwtcompanion.gacha

/** 抽取排行 / TOP 榜的一条。 */
data class RankEntry(
    val name: String,
    val resourceId: Int,
    val qualityLevel: Int,
    val count: Int,
)

/**
 * 一张五星的「到手情况」。
 *
 * [pulls] 是**抽到它花了多少抽**：从上一个五星之后算起（最老的那张则从记录开头算起）。
 * 这是桌面版「详情」页里每张五星都会标出来的数字。
 */
data class FiveStarPull(
    val record: GachaRecord,
    val pulls: Int,
    /** 是否限定（UP）；false 就是常驻，也就是俗称的「歪」。 */
    val isUp: Boolean,
) {
    val name: String get() = record.name
    val resourceId: Int get() = record.resourceId
    val time: String get() = record.time
}

/**
 * 单个卡池的统计。
 *
 * **每个卡池都有一套完全独立的指标**，池与池之间不共享：
 * 保底进度、稀有度分布、五星平均、UP 平均、最欧最非、不歪率。
 * 这是照搬桌面版「详情」页的口径。
 */
data class PoolStats(
    val pool: GachaPool,
    /** 该池总抽数。 */
    val total: Int,
    val ssrCount: Int,
    val srCount: Int,
    val rCount: Int,
    /** 已垫：距最近一个五星抽了多少发。没有五星时等于总抽数。 */
    val pitySsr: Int,
    /** 四星已垫，会被最近的五星截断（五星同样会重置四星计数）。 */
    val pitySr: Int,
    /** 五星平均：总抽数 ÷ 五星数。 */
    val ssrAverage: Double,
    /** UP（限定）五星数量。 */
    val upSsrCount: Int,
    /** UP 平均：平均多少抽出一个限定五星。没有限定五星时为 0。 */
    val upAverage: Double,
    /** 该池 50/50 赢的次数；不适用该机制的池恒为 0。 */
    val nonBannerWins: Int,
    /** 该池 50/50 的总次数（大保底不计入分母）；不适用时恒为 0。 */
    val nonBannerTotal: Int,
    /** 最欧：单张五星花掉的最少抽数。 */
    val ssrBest: Int,
    /** 最非：单张五星花掉的最多抽数。 */
    val ssrWorst: Int,
    /** 该池抽到的五星，时间倒序，每张都带着自己的消耗抽数。 */
    val fiveStarPulls: List<FiveStarPull>,
) {
    val hasData: Boolean get() = total > 0

    /** 该池的不歪率；没有样本（或该池没有 50/50 机制）时返回 null。 */
    val nonBannerRate: Double?
        get() = if (nonBannerTotal == 0) null else nonBannerWins.toDouble() / nonBannerTotal

    /** UP 占全部五星的比例（和「不歪率」不是一回事：这个是构成比，那个是胜率）。 */
    val upRate: Double
        get() = if (ssrCount == 0) 0.0 else upSsrCount.toDouble() / ssrCount

    /** 某个星级在该池里的占比。 */
    fun share(count: Int): Double = if (total == 0) 0.0 else count.toDouble() / total
}

/** 全部卡池的汇总统计。 */
data class GachaStats(
    /**
     * 参与统计的记录（每个卡池内时间倒序）。
     * 界面上的排行等功能要用它，才能和上面这些数字保持同一口径。
     */
    val records: List<GachaRecord>,
    val totalPulls: Int,
    /** 折算星声：一抽 160。 */
    val totalAstrites: Int,
    val ssrCount: Int,
    val srCount: Int,
    val rCount: Int,
    val ssrAverage: Double,
    /** 限定（非常驻）五星数量。 */
    val upSsrCount: Int,
    /** 50/50 赢的次数（各池累加）。 */
    val nonBannerWins: Int,
    /** 50/50 的总次数（大保底不计入分母）。 */
    val nonBannerTotal: Int,
    val startDate: String,
    val endDate: String,
    val topSsr: RankEntry?,
    val topSr: RankEntry?,
    val topUpSsr: RankEntry?,
    val rolePulls: Int,
    val roleSsr: Int,
    val weaponPulls: Int,
    val weaponSsr: Int,
    val pools: List<PoolStats>,
) {
    val isEmpty: Boolean get() = totalPulls == 0

    /** 五星不歪率（50/50 胜率）。没有样本时返回 null。 */
    val nonBannerRate: Double?
        get() = if (nonBannerTotal == 0) null else nonBannerWins.toDouble() / nonBannerTotal

    val roleAverage: Double get() = if (roleSsr == 0) 0.0 else rolePulls.toDouble() / roleSsr
    val weaponAverage: Double get() = if (weaponSsr == 0) 0.0 else weaponPulls.toDouble() / weaponSsr

    /** 有数据的卡池。 */
    val activePools: List<PoolStats> get() = pools.filter { it.hasData }
}

/**
 * 抽卡统计。纯函数，不碰网络和文件，方便直接跑单元测试。
 *
 * 约定：传入的记录按「每个卡池内时间倒序（索引 0 = 最新）」排列，
 * 这和官方接口的返回顺序一致。五星的保底/平均都依赖这个顺序。
 */
object GachaStatsCalculator {

    /** 一抽折算多少星声。 */
    const val ASTRITES_PER_PULL = 160

    /**
     * 五星硬保底抽数。
     *
     * 只用来画每张五星的消耗进度条（`pulls / 80`），桌面版也是按 80 算的。
     * 它不参与任何统计计算 —— 保底进度是从真实数据里数出来的，不是推算的。
     */
    const val FIVE_STAR_PITY = 80

    /**
     * 把新抓到的数据并进本地历史。
     *
     * 规则（按卡池分别处理）：
     *  - 新数据为空 → 原样保留旧的（抓取失败时绝不能把历史弄丢）
     *  - 旧数据为空 → 直接用新的
     *  - 否则：以新数据里**最老的时间**为界，这段区间里凡是旧数据有、新数据没有的，
     *    全部当成「这次没抓到」补回来
     *
     * 为什么用多重集差值而不是按时间一刀切：同一次十连的记录时间戳完全相同，
     * 而接口一次能返回的条数有限，十连可能正好跨在分页边界上。也不能按
     * 「物品 + 时间」去重 —— 同一次十连里抽到两把一样的三星武器是完全正常的。
     *
     * 覆盖范围取 `>= 边界` 而不是只取边界那一刻，是为了**不丢数据**：如果来的是一份
     * 比本地更旧的存档（比如又导入了一次老的 pool.json），只比边界那一刻的话，
     * 本地比它新的记录会被整段抹掉。桌面版就有这个毛病（CardPoolRequestTask.update
     * 只补旧数据里严格更老的那一段），只不过它每天会把 pool.json 备份一份兜底，
     * 手机上没有这层保险，所以这里比它保守一点。
     *
     * 结果保持「每个卡池内时间倒序」，且重复调用是幂等的。
     */
    fun merge(old: List<GachaRecord>, new: List<GachaRecord>): List<GachaRecord> {
        val poolIds = (old.map { it.poolId } + new.map { it.poolId }).distinct().sorted()
        return poolIds.flatMap { poolId ->
            val o = old.filter { it.poolId == poolId }
            val n = new.filter { it.poolId == poolId }
            when {
                n.isEmpty() -> o
                o.isEmpty() -> n
                else -> {
                    val boundary = n.minOf { it.time }
                    // 多重集差值：新数据里已经有的，从旧数据那份里逐条扣掉
                    val leftover = o.filter { it.time >= boundary }
                        .groupingBy { it }.eachCount().toMutableMap()
                    n.filter { it.time >= boundary }.forEach { r ->
                        val left = leftover[r] ?: 0
                        if (left <= 1) leftover.remove(r) else leftover[r] = left - 1
                    }
                    val recovered = leftover.entries.flatMap { (r, c) -> List(c) { r } }
                    // 同一时刻内，新数据在前（它的顺序是接口给的，比旧存档可信）
                    (n + recovered + o.filter { it.time < boundary })
                        .sortedByDescending { it.time }
                }
            }
        }
    }

    /**
     * 传入的记录约定是「每个卡池内时间倒序（索引 0 = 最新）」，这和官方接口的
     * 返回顺序一致；五星的保底、段长都依赖这个顺序。
     */
    fun compute(records: List<GachaRecord>): GachaStats {
        // 先按池切开，保证「分池统计」和「汇总统计」用的是同一份数据
        val byPool = records.groupBy { it.poolId }
        val pools = GachaPool.entries.sortedBy { it.id }
            .map { computePool(it, byPool[it.id].orEmpty()) }
        val effective = pools.flatMap { byPool[it.pool.id].orEmpty() }

        val totalPulls = effective.sumOf { it.count }
        val fiveStars = effective.filter { it.qualityLevel == 5 }
        val ssrCount = fiveStars.size
        val times = effective.map { it.time }

        return GachaStats(
            records = effective,
            totalPulls = totalPulls,
            totalAstrites = totalPulls * ASTRITES_PER_PULL,
            ssrCount = ssrCount,
            srCount = effective.count { it.qualityLevel == 4 },
            rCount = effective.count { it.qualityLevel == 3 },
            ssrAverage = if (ssrCount == 0) 0.0 else totalPulls.toDouble() / ssrCount,
            upSsrCount = fiveStars.count { it.resourceId !in STANDARD_FIVE_STAR_IDS },
            nonBannerWins = pools.sumOf { it.nonBannerWins },
            nonBannerTotal = pools.sumOf { it.nonBannerTotal },
            startDate = times.minOrNull()?.take(10).orEmpty(),
            endDate = times.maxOrNull()?.take(10).orEmpty(),
            topSsr = topOf(fiveStars),
            topSr = topOf(effective.filter { it.qualityLevel == 4 }),
            topUpSsr = topOf(fiveStars.filter { it.resourceId !in STANDARD_FIVE_STAR_IDS }),
            rolePulls = effective.inCategory(GachaPool.Category.CHARACTER).sumOf { it.count },
            roleSsr = effective.inCategory(GachaPool.Category.CHARACTER).count { it.qualityLevel == 5 },
            weaponPulls = effective.inCategory(GachaPool.Category.WEAPON).sumOf { it.count },
            weaponSsr = effective.inCategory(GachaPool.Category.WEAPON).count { it.qualityLevel == 5 },
            pools = pools,
        )
    }

    /**
     * 抽取排行：按物品名聚合、按抽数倒序。
     *
     * @param category 只要角色池 / 只要武器池；null = 全部
     * @param qualityLevel 只要这个星级；0 = 全部
     */
    fun rank(
        records: List<GachaRecord>,
        category: GachaPool.Category? = null,
        qualityLevel: Int = 0,
    ): List<RankEntry> = records
        .asSequence()
        .filter { category == null || GachaPool.byId(it.poolId)?.category == category }
        .filter { qualityLevel == 0 || it.qualityLevel == qualityLevel }
        .groupBy { it.name }
        .map { (name, list) ->
            RankEntry(
                name = name,
                resourceId = list.first().resourceId,
                qualityLevel = list.first().qualityLevel,
                count = list.sumOf { it.count },
            )
        }
        .sortedWith(compareByDescending<RankEntry> { it.count }.thenBy { it.name })

    // ---------------------------------------------------------------- 内部实现

    private fun computePool(pool: GachaPool, list: List<GachaRecord>): PoolStats {
        val size = list.size
        val ssrIdx = list.indices.filter { list[it].qualityLevel == 5 }
        val srIdx = list.indices.filter { list[it].qualityLevel == 4 }

        val newestSsr = ssrIdx.firstOrNull() ?: size
        val newestSr = srIdx.firstOrNull() ?: size

        // 每张五星「花了多少抽」：它和下一个更老的五星之间的距离；
        // 最老的那张则是从记录开头算到它。桌面版就是这么分段的。
        val segments = ssrIdx.mapIndexed { i, idx ->
            if (i < ssrIdx.lastIndex) ssrIdx[i + 1] - idx else size - idx
        }

        val fiveStarPulls = ssrIdx.mapIndexed { i, idx ->
            val record = list[idx]
            FiveStarPull(
                record = record,
                pulls = segments[i],
                isUp = record.resourceId !in STANDARD_FIVE_STAR_IDS,
            )
        }

        // UP 平均：只看限定五星，算它和上一个限定之间的距离
        val upIdx = ssrIdx.filter { list[it].resourceId !in STANDARD_FIVE_STAR_IDS }
        val upSegments = upIdx.mapIndexed { i, idx ->
            if (i < upIdx.lastIndex) upIdx[i + 1] - idx else size - idx
        }

        val (wins, total) = fiftyFiftyOf(pool, list)

        return PoolStats(
            pool = pool,
            total = list.sumOf { it.count },
            ssrCount = ssrIdx.size,
            srCount = srIdx.size,
            rCount = list.count { it.qualityLevel == 3 },
            pitySsr = newestSsr,
            pitySr = minOf(newestSr, newestSsr),
            // 只对**已完成的**五星段取平均，不含「当前这一轮还没出金」的垫抽。
            // 桌面版就是这么算的（CardPoolAnalysisTask: 段长求和 ÷ 五星数），
            // 拿总抽数直接除会把垫的抽数也算成产出，结果能比同卡的「最非」还大。
            ssrAverage = if (segments.isEmpty()) 0.0 else segments.average(),
            upSsrCount = upIdx.size,
            upAverage = if (upSegments.isEmpty()) 0.0 else upSegments.average(),
            nonBannerWins = wins,
            nonBannerTotal = total,
            ssrBest = segments.minOrNull() ?: 0,
            ssrWorst = segments.maxOrNull() ?: 0,
            fiveStarPulls = fiveStarPulls,
        )
    }

    private fun List<GachaRecord>.inCategory(c: GachaPool.Category) =
        filter { GachaPool.byId(it.poolId)?.category == c }

    /**
     * 单个卡池的五星不歪率（50/50 胜率）。
     *
     * 按时间从旧到新过一遍：抽到限定五星算赢；抽到常驻五星算输，且**下一个五星
     * 是大保底**，必定是限定，所以不计入分母。
     *
     * 保底状态**每个池独立重置**（池与池之间保底不共享），因此某个池开头的
     * 一发输赢无法判断是否带着上个池的保底。只有角色限定池有这个机制。
     *
     * 遍历顺序用**下标倒序**（而不是 `sortedBy { time }`）：时间只精确到秒，
     * 同一次十连里出两个五星时它们的 time 完全相同，而稳定排序会保留
     * 「新的在前」的原顺序，等于把同一秒内的先后弄反，直接算错输赢。
     * 桌面版也是按下标倒着走的（CardPoolAnalysisTask 的 `i--` 循环）。
     */
    private fun fiftyFiftyOf(pool: GachaPool, list: List<GachaRecord>): Pair<Int, Int> {
        if (!pool.hasFiftyFifty) return 0 to 0
        val fiveStars = list.filter { it.qualityLevel == 5 }.asReversed()
        var wins = 0
        var total = 0
        var guaranteed = false
        for (r in fiveStars) {
            if (guaranteed) {
                guaranteed = false // 大保底，不计入分母
                continue
            }
            total++
            if (r.resourceId !in STANDARD_FIVE_STAR_IDS) wins++ else guaranteed = true
        }
        return wins to total
    }

    private fun topOf(list: List<GachaRecord>): RankEntry? = list
        .groupBy { it.name }
        .map { (name, group) ->
            RankEntry(name, group.first().resourceId, group.first().qualityLevel, group.sumOf { it.count })
        }
        .maxByOrNull { it.count }
}
