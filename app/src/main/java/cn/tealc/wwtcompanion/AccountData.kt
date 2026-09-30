package cn.tealc.wwtcompanion

/**
 * 账号概览数据。
 * 对应官方 queryRole 返回里的 Base（基础）+ BattlePass（电台/战令）两个节点。
 */
data class AccountData(
    val roleId: String,
    val roleName: String,
    val region: String,
    val level: Int,                 // 等级
    val worldLevel: Int,            // 世界等级
    val activeDays: Int,            // 活跃天数
    val energy: Int,                // 体力
    val maxEnergy: Int,
    val energyRecoverTime: Long,    // 体力回满的时间戳(ms)，0 = 已满
    val storeEnergy: Int,           // 结晶单质
    val maxStoreEnergy: Int,
    val storeEnergyRecoverTime: Long, // 结晶单质回满时间戳(ms)，0 = 未提供
    val liveness: Int,              // 活跃度
    val livenessMax: Int,
    val weeklyInstCount: Int,       // 周本剩余
    val battlePassLevel: Int,       // 电台等级
    val battlePassWeekExp: Int,     // 电台本周经验
    val battlePassWeekMaxExp: Int,  // 电台本周经验上限
    val chestBoxes: List<Int>,      // 奇藏箱：朴素 / 基准 / 精密 / 辉光
    val phantomBoxes: List<Int>,    // 声骸宝箱（潮汐之遗）：绿 / 紫 / 金
)
