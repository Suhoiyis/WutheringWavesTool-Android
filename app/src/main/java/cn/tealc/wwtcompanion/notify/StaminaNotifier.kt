package cn.tealc.wwtcompanion.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.tealc.wwtcompanion.MainActivity
import cn.tealc.wwtcompanion.R

/**
 * 体力提醒的通知出口：建渠道、查权限、发通知。
 *
 * 三种通知用不同的 id，所以「快满」和「已满」可以同时在通知栏里，不会互相覆盖。
 */
object StaminaNotifier {

    private const val CHANNEL_ID = "stamina"
    private const val ID_SOON = 1001
    private const val ID_FULL = 1002
    private const val ID_TEST = 1003

    /** 渠道必须在发通知之前建好；重复调用无副作用 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "体力提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "体力快满、以及真正回满时提醒"
            },
        )
    }

    /** Android 13 起发通知需要运行时权限 */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun notifySoon(context: Context, minutesLeft: Long): Boolean =
        post(context, ID_SOON, "体力快满了", "还有约 $minutesLeft 分钟就回满")

    fun notifyFull(context: Context): Boolean =
        post(context, ID_FULL, "体力已满", "回复已经停止，上线清一下吧")

    /** 设置页的「测试通知」：不经过任何判定，直接发一条，用来确认权限和渠道正常 */
    fun notifyTest(context: Context): Boolean =
        post(context, ID_TEST, "测试通知", "如果你看到这条，说明通知权限和渠道都正常")

    private fun post(context: Context, id: Int, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_stamina)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        manager.notify(id, notification)
        return true
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
