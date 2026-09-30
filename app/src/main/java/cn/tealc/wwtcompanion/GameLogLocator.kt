package cn.tealc.wwtcompanion

import android.os.Environment
import java.io.File

/**
 * 鸣潮客户端写下 [Client.log] 的位置。
 *
 * 抽卡分析的数据链路是：
 * ```
 *   Client.log（XOR 加密）
 *     → 正则抠出 https://.../aki/gacha/index.html#/record?...&record_id=...&resources_id=...
 *     → 用 URL 里的参数 POST 官方抽卡接口（不需要 oauthCode，令牌就在 URL 里）
 * ```
 * PC 版和安卓版的日志路径结构完全一致（都是 UE 的 `Saved/Logs`），只有根目录不同。
 *
 * ## 为什么这个类只提供「路径」而不负责读文件
 *
 * 安卓 11 起，**其他应用的 `Android/data` 目录对普通应用一律不可读**，连
 * `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）也不例外 —— 这不是猜测，已在本机
 * （Android 16，`allFilesAccess=true`）实测确认：`File.exists()` 直接返回 false。
 *
 * 能读到该目录的只有 shell 身份（uid 2000）。因此只有两条路：
 *  - **Shizuku**：应用借用 shell 身份，可以 `cat` 出日志（手机端全自动，推荐）
 *  - **adb**：`adb shell cat <路径>`（需要连电脑）
 *
 * 所以这里只负责给出路径字符串，真正的读取交给上面两者之一。
 */
object GameLogLocator {

    /** 已知的鸣潮包名：国际服 / 国服。 */
    private val PACKAGES = listOf(
        "com.kurogame.wutheringwaves.global",
        "com.kurogame.mingchao",
    )

    /** 包名 files 目录之后的日志相对路径。 */
    private const val LOG_SUFFIX = "UE4Game/Client/Client/Saved/Logs/Client.log"

    /**
     * shell 视角下所有候选日志的绝对路径，按优先级排列。
     *
     * 注意用 `/storage/emulated/0` 而不是 `/sdcard`：两者等价，但前者是真实挂载路径，
     * 交给外部 shell 执行时更不容易出意外。
     */
    fun logPaths(): List<String> {
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return emptyList()
        return PACKAGES.map { "$root/Android/data/$it/files/$LOG_SUFFIX" }
    }

    /** 应用自身视角的候选文件对象。除调试外基本读不到，见类注释。 */
    fun candidates(): List<File> = logPaths().map(::File)
}
