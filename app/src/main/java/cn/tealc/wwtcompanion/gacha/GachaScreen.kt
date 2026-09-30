package cn.tealc.wwtcompanion.gacha

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.tealc.wwtcompanion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 五星 / 四星 / 三星的标识色，沿用游戏里的金紫蓝。 */
private val SSR_COLOR = Color(0xFFD9A441)
private val SR_COLOR = Color(0xFF9B6BD6)
private val R_COLOR = Color(0xFF5B8FC7)

private val TABS = listOf("统计", "卡池", "记录")

/** 记录列表每页条数。 */
private const val RECORD_PAGE_SIZE = 50

/**
 * 抽卡分析主界面。
 *
 * 数据的唯一来源是本地存档；更新记录需要用户粘贴一次抽卡链接，
 * 或者只粘一个 `record_id` —— 剩下的参数（UID / 区服 / 语言）都能推出来。
 *
 * @param accountPlayerId 账号界面已经拿到的 roleId，也就是抽卡接口要的 playerId
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GachaScreen(store: GachaStore, onBack: () -> Unit, accountPlayerId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 只读一次盘：结果同时供记录本体和「读失败的原因」使用
    val loaded = remember { store.load() }
    var records by remember { mutableStateOf(loaded.records) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // 打开时如果存档就读不出来，这里会带上原因，而不是假装「还没有数据」
    var status by remember { mutableStateOf(loaded.error.orEmpty()) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf("") }
    // 非 null 时显示「导出完整链接」对话框
    var exportParams by remember { mutableStateOf<GachaParams?>(null) }

    val stats = remember(records) { GachaStatsCalculator.compute(records) }

    BackHandler { if (showPaste) showPaste = false else onBack() }

    /** 解析并合并一批新记录 */
    fun applyIncoming(incoming: List<GachaRecord>, note: String) {
        val merged = GachaStatsCalculator.merge(records, incoming)
        records = merged
        status = if (store.save(merged)) {
            "$note，当前共 ${merged.sumOf { it.count }} 抽"
        } else {
            "$note，但**写入存档失败**，退出后这批数据会丢"
        }
    }

    // PC 端用 adb 推进来的文件（tools/oauth_code.py --push-gacha），打开即自动导入
    LaunchedEffect(Unit) {
        val dropped = withContext(Dispatchers.IO) { store.consumeDroppedImport() }
            ?: return@LaunchedEffect
        if (dropped.records.isEmpty()) {
            status = "导入失败：${dropped.error ?: "文件里没有可识别的数据"}"
        } else {
            applyIncoming(dropped.records, "已导入 PC 推送的 ${dropped.records.size} 条记录")
            if (dropped.unknownPools.isNotEmpty()) {
                status += "；有 ${dropped.unknownPools.size} 个卡池没认出来（${dropped.unknownPools.first()}）"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "正在读取文件…"
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (text == null) {
                status = "读取文件失败"
            } else {
                runCatching { GachaJson.parseDesktopPool(text) }
                    .onSuccess { parsed ->
                        if (parsed.records.isEmpty()) {
                            // 说「已导入 0 条」等于什么都没说，用户根本不知道哪不对
                            status = parsed.unknownPools.firstOrNull()?.let {
                                "这个文件里没有能识别的卡池（看到的是「$it」）"
                            } ?: "这个文件里没有任何卡池数据"
                        } else {
                            applyIncoming(parsed.records, "已导入 ${parsed.records.size} 条历史记录")
                            if (parsed.unknownPools.isNotEmpty()) {
                                status += "；有 ${parsed.unknownPools.size} 个卡池没认出来（${parsed.unknownPools.first()}）"
                            }
                        }
                    }
                    .onFailure { status = "解析失败：${it.message}" }
            }
            busy = false
        }
    }

    // 上次成功用过的参数。令牌在有效期内时可以直接重抓，不用把链接再粘一遍
    var savedParams by remember { mutableStateOf(store.loadParams()) }

    fun doFetch(params: GachaParams) {
        scope.launch {
            busy = true
            status = "正在获取卡池数据…"
            when (val result = GachaApi.fetch(params)) {
                is GachaFetchResult.Success -> {
                    // 令牌存不下来不影响这次展示，下次大不了再粘一遍
                    store.saveParams(params)
                    savedParams = params
                    applyIncoming(result.records, "已获取 ${result.records.size} 条记录")
                    if (result.failedPools.isNotEmpty()) {
                        status += "；${result.failedPools.size} 个卡池没取到"
                    }
                }
                is GachaFetchResult.InvalidToken ->
                    status = "链接已失效（${result.reason}）。请到游戏里打开一次「唤取记录」页面重新获取链接"
                is GachaFetchResult.Failure ->
                    status = "获取失败：${result.message}"
            }
            busy = false
        }
    }

    fun fetchFromUrl(input: String, uid: String?) {
        pasteError = ""
        val params = GachaUrlParser.parseLoose(input, uid ?: savedParams?.playerId)
        if (params == null) {
            // 报错要显示在对话框里 —— 对话框挡着，写到外面的状态栏用户根本看不见
            pasteError = if (GachaUrlParser.looksLikeBareRecordId(input)) {
                "只给 record_id 的话，还需要知道你的玩家 UID"
            } else {
                "没能从这段内容里认出抽卡链接，也不是一个 record_id"
            }
            return
        }
        showPaste = false
        doFetch(params)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        GachaTopBar(
            busy = busy,
            onBack = onBack,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onPaste = { pasteError = ""; showPaste = true },
            onExport = { savedParams?.let { exportParams = it } },
            canExport = savedParams != null,
        )

        // 刷新入口只有下拉一个。没有可用令牌时（还没导入过链接 / PC 推过数据），
        // 下拉就等同于「我要更新」—— 直接把粘贴对话框打开，而不是无声无息地什么都不做。
        PullToRefreshBox(
            isRefreshing = busy,
            onRefresh = {
                val params = savedParams
                when {
                    busy -> Unit // 上一次还没跑完，忽略这次下拉
                    params != null -> doFetch(params)
                    else -> { pasteError = ""; showPaste = true }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            if (records.isEmpty()) {
                EmptyState(busy = busy, status = status)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SegmentedTabs(TABS, tab) { tab = it }
                    if (status.isNotBlank()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (tab) {
                            0 -> StatsTab(stats)
                            1 -> PoolsTab(stats)
                            else -> RecordsTab(records)
                        }
                    }
                }
            }
        }
    }

    if (showPaste) {
        PasteUrlDialog(
            knownPlayerId = savedParams?.playerId ?: accountPlayerId,
            error = pasteError,
            onDismiss = { showPaste = false },
            onConfirm = { text, uid -> fetchFromUrl(text, uid) },
        )
    }

    exportParams?.let { params ->
        ExportUrlDialog(
            url = remember(params) { GachaUrlBuilder.build(params) },
            onDismiss = { exportParams = null },
        )
    }
}

// ---------------------------------------------------------------------- 顶栏

@Composable
private fun GachaTopBar(
    busy: Boolean,
    canExport: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "抽卡分析",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (canExport) {
            // 只贴了 record_id 时链接是不全的，这个入口把它还原成完整链接，
            // 好粘到别的统计工具里
            IconButton(onClick = onExport) {
                Icon(
                    painter = painterResource(R.drawable.ic_export),
                    contentDescription = "导出完整链接",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onImport, enabled = !busy) {
            Icon(
                painter = painterResource(R.drawable.ic_import),
                contentDescription = "导入历史数据",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPaste, enabled = !busy) {
            Icon(
                painter = painterResource(R.drawable.ic_link),
                contentDescription = "粘贴抽卡链接",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 空态。
 *
 * 用 LazyColumn 而不是纯 Column 包一层：下拉刷新靠嵌套滚动接收手势，
 * 这里如果没有可滚动容器，用户在空态下是**拉不动**的 —— 而空态恰恰是最需要刷新的时刻。
 */
@Composable
private fun EmptyState(busy: Boolean, status: String) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyStateBody(busy = busy, status = status)
            }
        }
    }
}

@Composable
private fun EmptyStateBody(busy: Boolean, status: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有抽卡数据",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "可以从两种情况里选一种：\n\n" +
                "· 有历史数据文件（桌面版的 pool.json）→ 用右上角第一个按钮导入\n" +
                "· 想看最新的 → 打开游戏里「唤取记录」页面，复制链接后用第二个按钮粘贴\n" +
                "    （链接太长的话，只复制里面的 record_id 也行）\n" +
                "· 以后想更新，直接下拉刷新即可",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (busy) "正在处理…" else status.ifBlank { "等待操作" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 分段切换

@Composable
private fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- 统计

@Composable
private fun StatsTab(stats: GachaStats) {
    var rankFilter by rememberSaveable { mutableStateOf(0) } // 0 全部 / 1 角色 / 2 武器
    val rank = remember(stats, rankFilter) {
        GachaStatsCalculator.rank(
            records = stats.records,
            category = when (rankFilter) {
                1 -> GachaPool.Category.CHARACTER
                2 -> GachaPool.Category.WEAPON
                else -> null
            },
        ).take(10)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMetric("总抽数", stats.totalPulls.toString(), Modifier.weight(1f))
                BigMetric("星声消耗", formatThousands(stats.totalAstrites), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMetric("五星", stats.ssrCount.toString(), Modifier.weight(1f), SSR_COLOR)
                BigMetric("四星", stats.srCount.toString(), Modifier.weight(1f), SR_COLOR)
                BigMetric("三星", stats.rCount.toString(), Modifier.weight(1f), R_COLOR)
            }
        }
        item {
            SectionCard("五星战绩") {
                MetricRow(
                    label = "不歪率",
                    value = stats.nonBannerRate
                        ?.let { "${stats.nonBannerWins}/${stats.nonBannerTotal}  ${percent(it)}" }
                        ?: "暂无样本",
                )
                stats.nonBannerRate?.let { rate ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { rate.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = SSR_COLOR,
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetricRow("五星平均", "%.1f 抽".format(stats.ssrAverage))
                MetricRow("UP 五星", "${stats.upSsrCount} 个")
                MetricRow("时间范围", "${stats.startDate} ~ ${stats.endDate}")
            }
        }
        item {
            SectionCard("角色 / 武器") {
                MetricRow(
                    "角色唤取",
                    "${stats.rolePulls} 抽 · ${stats.roleSsr} 金 · 平均 %.1f".format(stats.roleAverage),
                )
                Spacer(Modifier.height(6.dp))
                MetricRow(
                    "武器唤取",
                    "${stats.weaponPulls} 抽 · ${stats.weaponSsr} 金 · 平均 %.1f".format(stats.weaponAverage),
                )
            }
        }
        item {
            SectionCard("抽取排行") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "角色", "武器").forEachIndexed { index, label ->
                        val active = index == rankFilter
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { rankFilter = index },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (rank.isEmpty()) {
            item { Hint("这个筛选下没有记录") }
        } else {
            items(rank, key = { it.name }) { entry ->
                RankRow(entry, max = rank.first().count)
            }
        }
    }
}

@Composable
private fun RankRow(entry: RankEntry, max: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GachaAvatar(entry.resourceId, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                StarBadge(entry.qualityLevel)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (max <= 0) 0f else entry.count.toFloat() / max },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = rarityColor(entry.qualityLevel),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "×${entry.count}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------- 卡池

@Composable
private fun PoolsTab(stats: GachaStats) {
    val active = stats.pools.filter { it.hasData }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active.isEmpty()) {
            item { Hint("还没有任何卡池记录") }
        }
        items(active, key = { it.pool.id }) { pool -> PoolCard(pool) }
    }
}

@Composable
private fun PoolCard(pool: PoolStats) {
    SectionCard(pool.pool.displayName) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = pool.total.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "抽",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "五星已垫 ${pool.pitySsr} 抽",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(10.dp))
        RarityBar(pool)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RarityLegend("五星", pool.ssrCount, pool.share(pool.ssrCount), SSR_COLOR)
            RarityLegend("四星", pool.srCount, pool.share(pool.srCount), SR_COLOR)
            RarityLegend("三星", pool.rCount, pool.share(pool.rCount), R_COLOR)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))

        // 下面这些全是「本池独立」的指标，不跟其他池混算
        MetricRow("四星已垫", "${pool.pitySr} 抽")
        MetricRow(
            label = "本池消耗",
            value = "${formatThousands(pool.total * GachaStatsCalculator.ASTRITES_PER_PULL)} 星声",
        )
        MetricRow("五星平均", if (pool.ssrCount == 0) "\u2014" else "%.1f 抽".format(pool.ssrAverage))
        MetricRow("UP 平均", if (pool.upSsrCount == 0) "\u2014" else "%.1f 抽".format(pool.upAverage))
        MetricRow(
            label = "最欧 / 最非",
            value = if (pool.ssrCount == 0) "\u2014" else "${pool.ssrBest} / ${pool.ssrWorst} 抽",
        )
        MetricRow(
            label = "不歪率",
            value = pool.nonBannerRate
                ?.let { "${pool.nonBannerWins}/${pool.nonBannerTotal}  ${percent(it)}" }
                ?: "\u2014",
        )
        MetricRow(
            label = "UP 占比",
            value = if (pool.ssrCount == 0) "\u2014"
            else "${pool.upSsrCount}/${pool.ssrCount}  ${percent(pool.upRate)}",
        )

        if (pool.fiveStarPulls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            pool.fiveStarPulls.forEach { FiveStarRow(it) }
        }
    }
}

/**
 * 一张五星的明细行：头像、名字、UP 标记、时间，以及**抽到它花了多少抽**。
 *
 * 这里对齐桌面版「详情」页的列表：`count` 就是那一张的段长，
 * 进度条按 80 抽硬保底折算，方便一眼看出这张是欧是非。
 */
@Composable
private fun FiveStarRow(pull: FiveStarPull) {
    val accent = if (pull.isUp) SSR_COLOR else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GachaAvatar(pull.resourceId, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pull.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (pull.isUp) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SSR_COLOR.copy(alpha = 0.22f),
                    ) {
                        Text(
                            text = "UP",
                            style = MaterialTheme.typography.labelSmall,
                            color = SSR_COLOR,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = pull.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${pull.pulls} 抽",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = {
                    (pull.pulls.toFloat() / GachaStatsCalculator.FIVE_STAR_PITY).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .width(72.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accent,
            )
        }
    }
}

/** 五星/四星/三星的占比条，一眼看出这个池的出金密度。 */
@Composable
private fun RarityBar(pool: PoolStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        listOf(
            pool.ssrCount to SSR_COLOR,
            pool.srCount to SR_COLOR,
            pool.rCount to R_COLOR,
        ).forEach { (count, color) ->
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun RarityLegend(label: String, count: Int, share: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(text = "$label $count", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "%.1f%%".format(share * 100),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------- 记录

@Composable
private fun RecordsTab(records: List<GachaRecord>) {
    var poolFilter by rememberSaveable { mutableStateOf(0) } // 0 = 全部
    var page by rememberSaveable { mutableStateOf(0) }

    val filtered = remember(records, poolFilter) {
        if (poolFilter == 0) records else records.filter { it.poolId == poolFilter }
    }
    val pageCount = maxOf(1, (filtered.size + RECORD_PAGE_SIZE - 1) / RECORD_PAGE_SIZE)
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageItems = filtered.drop(safePage * RECORD_PAGE_SIZE).take(RECORD_PAGE_SIZE)
    val poolsWithData = remember(records) {
        GachaPool.entries.filter { p -> records.any { it.poolId == p.id } }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("全部", poolFilter == 0) { poolFilter = 0; page = 0 }
            poolsWithData.forEach { pool ->
                FilterChip(pool.displayName, poolFilter == pool.id) {
                    poolFilter = pool.id
                    page = 0
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${filtered.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { page = safePage - 1 }, enabled = safePage > 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_l),
                    contentDescription = "上一页",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${safePage + 1} / $pageCount",
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = { page = safePage + 1 }, enabled = safePage < pageCount - 1) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_r),
                    contentDescription = "下一页",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            items(pageItems) { r ->
                RecordRow(r)
            }
        }
    }
}

@Composable
private fun RecordRow(r: GachaRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GachaAvatar(r.resourceId, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                StarBadge(r.qualityLevel)
            }
            Text(
                text = GachaPool.byId(r.poolId)?.displayName.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = r.time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ------------------------------------------------------------------ 粘贴对话框

/**
 * 粘贴对话框。
 *
 * 知道 UID 的时候（存档里有，或者账号界面已经拿到了 roleId）就只留一个输入框，
 * 用户可以把整条链接贴进来，也可以**只贴 record_id** —— 区服能由 UID 首位推出来。
 * 不知道 UID 时才多问一句。
 */
@Composable
private fun PasteUrlDialog(
    knownPlayerId: String?,
    error: String,
    onDismiss: () -> Unit,
    onConfirm: (text: String, uid: String?) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var uid by rememberSaveable { mutableStateOf(knownPlayerId.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴抽卡链接") },
        text = {
            Column {
                Text(
                    text = "在游戏里打开「唤取记录」页面，把链接整条复制过来。\n" +
                        "也可以只贴 record_id（那串 32 位十六进制），其余参数会自动补上。\n" +
                        "链接本身就是令牌，不会上传到任何第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("整条链接，或者只有 record_id") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 只粘了一串令牌时给个字数提示：少一位就查不出东西，肉眼又看不出来
                val token = input.trim()
                if (token.isNotEmpty() && !token.contains("://") && !token.contains('=')) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (token.length == 32) {
                            "record_id 长度正确"
                        } else {
                            "record_id 应该是 32 位，当前 ${token.length} 位"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (token.length == 32) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (knownPlayerId == null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uid,
                        onValueChange = { uid = it },
                        label = { Text("玩家 UID") },
                        placeholder = { Text("802104525") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input, uid.takeIf { it.isNotBlank() }) },
                enabled = input.isNotBlank(),
            ) {
                Text("获取")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ------------------------------------------------------------------ 导出链接对话框

/**
 * 把参数还原成的完整链接显示出来，方便粘到别的统计工具里。
 *
 * 用只读输入框而不是普通 Text：Android 上长按就能「全选 / 复制」，
 * 比自己去点一个复制按钮更顺手（复制按钮也留着，一步到位）。
 */
@Composable
private fun ExportUrlDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("完整抽卡链接") },
        text = {
            Column {
                Text(
                    text = "player_id、record_id、svr_id、lang 来自你的数据；\n" +
                        "gacha_id、gacha_type、resources_id、platform 是照游戏格式补的占位值 ——\n" +
                        "官方接口不看这几个字段，粘到别的工具里也不影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已复制到剪贴板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 用平台 API 而不是已废弃的 LocalClipboardManager
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("抽卡链接", url))
                copied = true
            }) {
                Text("复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ------------------------------------------------------------------ 小零件

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StarBadge(level: Int) {
    Text(
        text = "★".repeat(level.coerceIn(1, 5)),
        style = MaterialTheme.typography.labelSmall,
        color = rarityColor(level),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        textAlign = TextAlign.Center,
    )
}

private fun rarityColor(level: Int): Color = when (level) {
    5 -> SSR_COLOR
    4 -> SR_COLOR
    else -> R_COLOR
}

private fun percent(value: Double): String = "%.2f%%".format(value * 100)

private fun formatThousands(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()
