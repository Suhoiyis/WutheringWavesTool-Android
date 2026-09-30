package cn.tealc.wwtcompanion.gacha

/**
 * 抽卡数据模型与卡池定义。
 *
 * 这里的字段名刻意和官方抽卡接口返回的 JSON 保持一致，方便直接映射。
 */
data class GachaRecord(
    /** 所属卡池编号 1..13，见 [GachaPool]。 */
    val poolId: Int,
    /** 接口返回的「调谐类型」，例如「角色精准调谐」。注意它和 [poolId] 不是一回事。 */
    val cardPoolType: String,
    val resourceId: Int,
    /** 星级：5 / 4 / 3。 */
    val qualityLevel: Int,
    /** 「角色」/「武器」/「道具」。 */
    val resourceType: String,
    val name: String,
    /** 这一条记录代表几次抽取，正常都是 1。 */
    val count: Int,
    /** 抽取时间，格式 `yyyy-MM-dd HH:mm:ss`。 */
    val time: String,
)

/**
 * 13 个卡池。
 *
 * [id] 就是请求接口时 `cardPoolType` 的取值；[displayName] 与官方中文名一一对应，
 * 也是桌面版 `pool.json` 里的键名（用于导入历史数据）。
 *
 * 判断「角色池 / 武器池」一律走这里的 [category]，**不再像桌面版那样去匹配
 * 中文字符串前缀** —— 那样一旦 lang 不是中文就会全盘失效。
 */
enum class GachaPool(
    val id: Int,
    val displayName: String,
    val category: Category,
    /** 是否参与「五星不歪率」统计：只有角色限定池才有 50/50 机制。 */
    val hasFiftyFifty: Boolean,
) {
    CHARACTER_EVENT(1, "角色活动唤取", Category.CHARACTER, true),
    WEAPON_EVENT(2, "武器活动唤取", Category.WEAPON, false),
    CHARACTER_STANDARD(3, "角色常驻唤取", Category.CHARACTER, false),
    WEAPON_STANDARD(4, "武器常驻唤取", Category.WEAPON, false),
    NOVICE(5, "新手唤取", Category.CHARACTER, false),
    NOVICE_SELECT(6, "新手自选唤取", Category.CHARACTER, false),
    NOVICE_GRATITUDE(7, "新手自选唤取（感恩定向唤取）", Category.CHARACTER, false),
    CHARACTER_NEW(8, "角色新旅唤取", Category.CHARACTER, true),
    WEAPON_NEW(9, "武器新旅唤取", Category.WEAPON, false),
    CHARACTER_COLLAB(10, "角色联动唤取", Category.CHARACTER, true),
    WEAPON_COLLAB(11, "武器联动唤取", Category.WEAPON, false),
    CHARACTER_MEMORY(12, "角色忆旅唤取", Category.CHARACTER, true),
    WEAPON_MEMORY(13, "武器忆旅唤取", Category.WEAPON, false),
    ;

    enum class Category { CHARACTER, WEAPON }

    companion object {
        fun byId(id: Int): GachaPool? = entries.firstOrNull { it.id == id }

        fun byDisplayName(name: String): GachaPool? =
            entries.firstOrNull { it.displayName == name }
    }
}

/**
 * 「常驻」五星 ID 集合 —— 用来判断在角色活动池里抽到的五星是 UP 还是歪。
 *
 * 落在集合里 = 常驻（歪了）；不在 = 限定（UP）。
 *
 * 角色部分就是俗称的「常驻五虎」，长期以来没有变动；
 * 后半段 `2101xxxx`~`2105xxxx` 是常驻五星武器。
 * 取自桌面版 `ui.analysis.base_role`（见 local.properties）。
 *
 * 需要留意：如果官方以后往常驻池加新五星，这份表要同步更新，否则那个角色会被
 * 误判成 UP。目前（3.7 版本）它和游戏一致。
 */
val STANDARD_FIVE_STAR_IDS: Set<Int> = setOf(
    // 常驻五星角色：凌阳 / 安可 / 卡卡罗 / 鉴心 / 维里奈
    1104, 1203, 1301, 1405, 1503,
    // 常驻五星武器
    21010015, 21020015, 21030015, 21040015, 21050015,
    21050045, 21030045, 21010045, 21020045, 21040045,
)
