package cn.tealc.wwtcompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import cn.tealc.wwtcompanion.gacha.GachaScreen
import cn.tealc.wwtcompanion.gacha.GachaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CredentialStore(this)
        setContent {
            // 跟随系统明暗，不提供应用内开关
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(store)
                }
            }
        }
    }
}

@Composable
private fun AppScreen(store: CredentialStore) {
    var oauthCode by remember { mutableStateOf(store.loadOauthCode()) }
    var account by remember { mutableStateOf<AccountData?>(null) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 抽卡分析是独立的一屏，通过左下角入口进入
    var showGacha by rememberSaveable { mutableStateOf(false) }

    // 服务器时间相对本机时钟的偏移，用于校正所有倒计时
    var clockOffset by remember { mutableStateOf(0L) }
    // 上次「发起查询」的时刻；切回前台时据此判断数据是否已经旧了
    var lastAttemptAt by remember { mutableStateOf(0L) }
    // 上次「查询成功」的时刻，用于展示「更新于 HH:mm」
    var updatedAt by remember { mutableStateOf(0L) }
    // 首次加载是否已经跑过（避免和下面的 ON_START 重复请求）
    var loadedOnce by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 查询逻辑抽出来，首次加载 / 手动刷新 / 切回前台共用
    val refresh: suspend () -> Unit = {
        loading = true
        status = "查询中…"
        lastAttemptAt = System.currentTimeMillis()
        when (val result = LauncherApi.fetchAccount(oauthCode.orEmpty())) {
            is ApiResult.Success -> {
                account = result.data
                clockOffset = result.clockOffsetMillis
                updatedAt = System.currentTimeMillis()
                status = ""
            }
            ApiResult.TokenExpired ->
                status = "oauthCode 已失效，请在 PC 启动器重新登录后重新获取"
            is ApiResult.Failure -> status = "失败：${result.message}"
        }
        loading = false
    }

    // 首次进入 / token 变化：无条件查一次
    LaunchedEffect(oauthCode) {
        if (!oauthCode.isNullOrBlank()) {
            refresh()
            loadedOnce = true
        }
    }

    // 从后台切回前台：数据放旧了就自动重查一次，免得看到的是几小时前的数字
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!loadedOnce) return@LifecycleEventEffect
        if (System.currentTimeMillis() - lastAttemptAt >= DATA_STALE_AFTER_MS) {
            scope.launch { refresh() }
        }
    }

    val saved = oauthCode
    when {
        // —— 首次使用：还没有任何凭证 ——
        saved.isNullOrBlank() -> FirstRunScreen(
            onSave = { value ->
                store.saveOauthCode(value)
                oauthCode = value
            },
        )

        // —— 设置页：只是「查看/替换凭证」，进来不写入任何东西 ——
        showSettings -> {
            // 系统返回键等同「返回」，绝不删除已有凭证
            BackHandler { showSettings = false }
            CredentialSettingsScreen(
                current = saved,
                onCancel = { showSettings = false },
                onSave = { value ->
                    if (value != saved) {
                        store.saveOauthCode(value)
                        account = null
                        updatedAt = 0L
                        status = ""
                        oauthCode = value
                    }
                    showSettings = false
                },
            )
        }

        // —— 抽卡分析：独立一屏，返回即回到主界面 ——
        showGacha -> {
            val context = LocalContext.current
            BackHandler { showGacha = false }
            GachaScreen(
                store = remember { GachaStore(context) },
                onBack = { showGacha = false },
                // 抽卡接口要的 playerId 就是账号的 roleId，直接复用，用户不用再填
                accountPlayerId = account?.roleId,
            )
        }

        // —— 主界面 ——
        else -> MainScreen(
            account = account,
            status = status,
            loading = loading,
            clockOffset = clockOffset,
            updatedAt = updatedAt,
            onRefresh = { scope.launch { refresh() } },
            onOpenSettings = { showSettings = true },
            onOpenGacha = { showGacha = true },
        )
    }
}

/** 首次使用：粘贴 oauthCode */
@Composable
private fun FirstRunScreen(onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "WWT Companion",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "首次使用，需要一个 oauthCode。\n" +
                "在电脑上运行 wwt-android/tools/oauth_code.py --push，可自动抓取并写入本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("oauthCode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val value = input.trim()
                if (value.isNotEmpty()) onSave(value)
            },
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
    }
}

/** 主界面：数据卡片 + 下拉刷新 + 右下角不起眼的「设置」齿轮 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    account: AccountData?,
    status: String,
    loading: Boolean,
    clockOffset: Long,
    updatedAt: Long,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGacha: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 底部留白，避免内容被右下角齿轮压住
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "WWT Companion",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                // 刷新只有下拉一个入口，所以这行副标题兼作操作提示 ——
                // 下拉手势本身没有任何视觉线索，不写出来用户不会知道。
                Text(
                    text = when {
                        loading -> "查询中…"
                        updatedAt > 0L -> "更新于 ${formatClock(updatedAt)} · 下拉可刷新"
                        else -> "下拉可刷新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                account?.let { InfoCard(it, clockOffset) }
            }
        }

        // 抽卡分析入口：与右下角的设置对称放在左下角。
        // 做得比设置显眼一点 —— 这是常用功能，而设置是怕误触才藏起来的。
        IconButton(
            onClick = onOpenGacha,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_gacha),
                contentDescription = "抽卡分析",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }

        // 凭证维护入口：做得小而不显眼，避免误触
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 设置页：查看 / 替换 oauthCode。
 *
 * 进到这里不会动任何已保存的数据；直接返回（或按系统返回键）即取消，
 * 依旧使用原来的凭证。
 */
@Composable
private fun CredentialSettingsScreen(
    current: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 用 rememberSaveable：转屏/进程重建后，输到一半的内容也不会丢
    var input by rememberSaveable(current) { mutableStateOf(current) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = "登录凭证 oauthCode",
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("oauthCode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "· 直接返回不会改动任何东西，仍使用原来的凭证\n" +
                "· 换账号时，在 PC 启动器重新登录后再取新的 oauthCode\n" +
                "· 电脑上运行 wwt-android/tools/oauth_code.py --push 可一键写入本机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
            Button(
                onClick = { onSave(input.trim()) },
                enabled = input.isNotBlank() && input.trim() != current,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun InfoCard(a: AccountData, clockOffset: Long) {
    // 接口给的都是服务器侧的绝对时间戳，所以「现在」也要用服务器时间算
    val now = rememberTicker() + clockOffset
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 角色标识
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${a.roleName}   Lv.${a.level}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "区服 ${a.region} · roleId ${a.roleId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // 四项主要数据（2×2）
            val energySubtitle =
                if (a.energyRecoverTime <= 0L) "已回满"
                else "距满 " + formatDuration(a.energyRecoverTime - now)
            // 结晶单质只有在体力满了之后才会开始累积，所以体力没满时
            // StoreEnergyRecoverTime 长期是 0；此时不显示倒计时，属正常现象。
            val storeEnergySubtitle =
                if (a.storeEnergyRecoverTime > 0L) "距满 " + formatDuration(a.storeEnergyRecoverTime - now)
                else null
            val weeklySubtitle = formatDuration(nextWeeklyResetMillis(now) - now) + "后刷新"

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem(
                    iconRes = R.drawable.ic_energy,
                    label = "体力",
                    current = a.energy,
                    max = a.maxEnergy,
                    subtitle = energySubtitle,
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    iconRes = R.drawable.ic_store_energy,
                    label = "结晶单质",
                    current = a.storeEnergy,
                    max = a.maxStoreEnergy,
                    subtitle = storeEnergySubtitle,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem(
                    iconRes = R.drawable.ic_liveness,
                    label = "活跃度",
                    current = a.liveness,
                    max = a.livenessMax,
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    iconRes = R.drawable.ic_weekly_inst,
                    label = "周本剩余",
                    current = a.weeklyInstCount,
                    max = WEEKLY_INST_LIMIT,
                    subtitle = weeklySubtitle,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // 电台（战令）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_battle_pass),
                    contentDescription = "电台",
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("电台 Lv.${a.battlePassLevel}", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${a.battlePassWeekExp} / ${a.battlePassWeekMaxExp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ratio(a.battlePassWeekExp, a.battlePassWeekMaxExp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider()

            // 奇藏箱（4 种）
            ChestSection(
                title = "奇藏箱",
                counts = a.chestBoxes,
                icons = listOf(
                    R.drawable.ic_chest_1,
                    R.drawable.ic_chest_2,
                    R.drawable.ic_chest_3,
                    R.drawable.ic_chest_4,
                ),
                names = listOf("朴素奇藏", "基准奇藏", "精密奇藏", "辉光奇藏"),
            )

            // 声骸宝箱（潮汐之遗）
            ChestSection(
                title = "声骸宝箱",
                counts = a.phantomBoxes,
                icons = listOf(
                    R.drawable.ic_phantom_1,
                    R.drawable.ic_phantom_2,
                    R.drawable.ic_phantom_3,
                ),
                names = listOf("潮汐之遗·绿", "潮汐之遗·紫", "潮汐之遗·金"),
            )

            HorizontalDivider()

            // 次级信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SubStat("世界等级", "${a.worldLevel}")
                SubStat("活跃天数", "${a.activeDays}")
            }
        }
    }
}

/** 单个数据项：图标 + 数值 + 名称 + 可选倒计时 + 进度条 */
@Composable
private fun StatItem(
    iconRes: Int,
    label: String,
    current: Int,
    max: Int?,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = if (max != null) "$current / $max" else "$current",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 没有副标题时用一个不换行空格占位，保证这一行的高度存在；
                // 否则同一行里两个 StatItem 的文字块高度不同，进度条就会错开。
                Text(
                    text = subtitle ?: "\u00A0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (max != null) {
            LinearProgressIndicator(
                progress = { ratio(current, max) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SubStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 宝箱区块：一行图标 + 数量 + 名称 */
@Composable
private fun ChestSection(
    title: String,
    counts: List<Int>,
    icons: List<Int>,
    names: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            icons.forEachIndexed { index, iconRes ->
                ChestItem(
                    iconRes = iconRes,
                    name = names.getOrElse(index) { "" },
                    count = counts.getOrElse(index) { 0 },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ChestItem(
    iconRes: Int,
    name: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = name,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 每秒刷新一次的「当前时间」，用于倒计时展示 */
@Composable
private fun rememberTicker(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** 把毫秒时长格式化为「3天5时12分」 */
private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0分"
    val totalMinutes = millis / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("${days}天")
        if (days > 0 || hours > 0) append("${hours}时")
        append("${minutes}分")
    }
}

/**
 * 下一次周本刷新时间：下周一 04:00（**服务器时区**，不是手机时区）。
 *
 * 启动器接口不提供该时间（Base 里只有 WeeklyInstCount），
 * 按游戏固定规则写死：每周一 04:00 刷新。
 *
 * 用服务器时区而不是 `Calendar.getInstance()`：港服/亚服按 UTC+8 的 04:00 刷新，
 * 手机时区跟着人走，出差到别的时区时按手机时区算会整体偏移。
 * 本机就在 UTC+8 时两者完全一致。
 */
private fun nextWeeklyResetMillis(nowMillis: Long): Long {
    val cal = Calendar.getInstance(SERVER_TIME_ZONE)
    cal.timeInMillis = nowMillis
    cal.set(Calendar.HOUR_OF_DAY, 4)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)       // 周日=1 ... 周一=2 ... 周六=7
    val daysSinceMonday = (dayOfWeek + 5) % 7           // 周一 -> 0
    cal.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)    // 回到本周一 04:00
    if (cal.timeInMillis <= nowMillis) {
        cal.add(Calendar.DAY_OF_MONTH, 7)               // 已过则顺延到下周
    }
    return cal.timeInMillis
}

/** 游戏刷新用的时区（港服/亚服 = UTC+8）。 */
private val SERVER_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

/**
 * 数据「保鲜期」：从后台切回前台时，若距上次查询已超过这个时长就自动重查。
 *
 * 体力 6 分钟才回 1 点，5 分钟以内的数据基本没变化，没必要来回请求。
 */
private const val DATA_STALE_AFTER_MS = 5 * 60 * 1000L

/**
 * 每周周本可领取次数的上限。
 *
 * 启动器接口只返回 Base.WeeklyInstCount（剩余次数），**不给上限**；
 * 只有库街区的接口才带 weeklyInstCountLimit，而港服用不了库街区。
 * 所以这里按游戏固定规则写死：每周 3 次。
 */
private const val WEEKLY_INST_LIMIT = 3

/** 把时间戳格式化成「HH:mm」，用于「更新于 …」 */
private fun formatClock(millis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

private fun ratio(current: Int, total: Int): Float =
    if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
