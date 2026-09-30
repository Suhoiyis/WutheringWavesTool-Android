package cn.tealc.wwtcompanion.gacha

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 角色/武器头像。
 *
 * 图片来自原作者的公开资源仓（桌面版用的同一份）：
 * ```
 * https://gitee.com/tealc/WutheringWavesToolResources/raw/main/assets/header/<resourceId>.png
 * ```
 * 文件名就是 `resourceId`，256×256 的 PNG。
 *
 * 策略是**按需下载 + 两级缓存**：内存 → 磁盘 → 网络。已看过一次的图不再联网，
 * 新角色也不用等应用更新。Gitee 是 302 跳到签名地址，OkHttp 默认会跟随。
 */
object AvatarCache {

    private const val BASE = "https://gitee.com/tealc/WutheringWavesToolResources/raw/main/assets/header/"

    /** Gitee 挂了时的备选源；国内多半不通，聊胜于无。 */
    private const val MIRROR_BASE =
        "https://raw.githubusercontent.com/leck995/WutheringWavesToolResources/main/assets/header/"

    /** 下载间隔，别给资源站添堵（桌面版也是这个节奏）。 */
    private const val DOWNLOAD_INTERVAL_MS = 120L

    private val memory = mutableMapOf<Int, ImageBitmap>()

    /** 保证同一张图不会被并发下两次。 */
    private val locks = mutableMapOf<Int, Mutex>()
    private val locksGuard = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fromMemory(resourceId: Int): ImageBitmap? = synchronized(memory) { memory[resourceId] }

    private fun cacheFile(context: Context, resourceId: Int) =
        File(File(context.filesDir, "avatars"), "$resourceId.png")

    /** 内存 → 磁盘 → 网络。全都拿不到时返回 null（界面显示占位）。 */
    suspend fun load(context: Context, resourceId: Int): ImageBitmap? {
        fromMemory(resourceId)?.let { return it }
        val mutex = locksGuard.withLock { locks.getOrPut(resourceId) { Mutex() } }
        return mutex.withLock {
            // 等锁期间别人可能已经下好了
            fromMemory(resourceId)?.let { return@withLock it }

            val file = cacheFile(context, resourceId)
            decode(file)?.let { put(resourceId, it); return@withLock it }

            val bytes = download(resourceId) ?: return@withLock null
            withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
            }
            decode(bytes)?.also { put(resourceId, it) }
        }
    }

    private fun put(resourceId: Int, bitmap: ImageBitmap) {
        synchronized(memory) { memory[resourceId] = bitmap }
    }

    private fun decode(file: File): ImageBitmap? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    private suspend fun download(resourceId: Int): ByteArray? {
        delay(DOWNLOAD_INTERVAL_MS)
        for (base in listOf(BASE, MIRROR_BASE)) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url("$base$resourceId.png").build())
                        .execute().use { resp ->
                            if (resp.isSuccessful) resp.body.bytes() else null
                        }
                }.getOrNull()
            }
            // 有些 CDN 出错时会回一段 HTML，用 PNG magic 挡一下
            if (bytes != null && bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
                return bytes
            }
        }
        return null
    }
}

/**
 * 圆形头像；还在下载或下载失败时显示一个淡淡的占位圆。
 *
 * 占位不显示「？」之类的东西 —— 列表里几十个头像同时转圈会很吵。
 */
@Composable
fun GachaAvatar(resourceId: Int, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(resourceId) { mutableStateOf(AvatarCache.fromMemory(resourceId)) }
    LaunchedEffect(resourceId) {
        if (bitmap == null) bitmap = AvatarCache.load(context, resourceId)
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        // 占位用一个低调的圆，不转圈也不显示问号 —— 一屏几十个会很吵
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
