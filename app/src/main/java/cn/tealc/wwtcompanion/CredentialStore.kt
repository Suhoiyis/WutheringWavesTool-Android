package cn.tealc.wwtcompanion

import android.content.Context

/**
 * 用 SharedPreferences 本地保存 oauthCode。
 *
 * 注意：oauthCode 等效于登录态，切勿硬编码进源码或上传到公开仓库。
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("wwt_companion", Context.MODE_PRIVATE)

    fun loadOauthCode(): String? = prefs.getString(KEY_OAUTH, null)

    fun saveOauthCode(value: String) {
        prefs.edit().putString(KEY_OAUTH, value).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_OAUTH).apply()
    }

    private companion object {
        const val KEY_OAUTH = "oauth_code"
    }
}
