package cn.tealc.wwtcompanion.gacha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GachaUrlParserTest {

    /**
     * 结构照抄真实链接，但 **record_id 是编的**。
     *
     * record_id 就是抽卡接口的令牌（有它就能读这个账号的全部抽卡记录），
     * 不能把真货写死在源码里。反正解析只看格式，编一个 32 位十六进制完全等价。
     */
    private val fakeRecordId = "0123456789abcdef0123456789abcdef"

    private val realUrl =
        "https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html#/record" +
            "?svr_id=919752ae5ea09c1ced910dd668a63ffb&player_id=802104525&lang=zh-Hans" +
            "&gacha_id=100084&gacha_type=1&svr_area=global" +
            "&record_id=$fakeRecordId" +
            "&resources_id=4e72d71659676be7c39c94f18f807740&platform=PC"

    @Test
    fun `解析完整链接`() {
        val p = GachaUrlParser.parse(realUrl)!!
        assertEquals("802104525", p.playerId)
        assertEquals(fakeRecordId, p.recordId)
        assertEquals("4e72d71659676be7c39c94f18f807740", p.cardPoolId)
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", p.serverId)
        assertEquals("zh-Hans", p.languageCode)
        assertEquals("global", p.serverArea)
    }

    @Test
    fun `可以解析被日志包住的整行内容`() {
        // 日志里链接通常后面跟着引号或方括号
        assertEquals("802104525", GachaUrlParser.parse("OpenWebView [sdkJson: {\"url\":\"$realUrl\"]}")
            ?.playerId)
        assertEquals("802104525", GachaUrlParser.parse("url=$realUrl\"}")
            ?.playerId)
    }

    @Test
    fun `可以只粘参数串`() {
        val p = GachaUrlParser.parse(
            "svr_id=abc&player_id=802104525&lang=zh-Hans&record_id=dead&resources_id=beef&svr_area=global"
        )!!
        assertEquals("802104525", p.playerId)
        assertEquals("dead", p.recordId)
        assertEquals("beef", p.cardPoolId)
        assertEquals("abc", p.serverId)
    }

    @Test
    fun `转义过的与号也能解析`() {
        val escaped = realUrl.replace("&", "\\u0026")
        assertEquals(fakeRecordId, GachaUrlParser.parse(escaped)?.recordId)
    }

    @Test
    fun `缺少必要参数时返回 null`() {
        assertNull(GachaUrlParser.parse(""))
        assertNull(GachaUrlParser.parse("随便一段文字"))
        // 有 player_id 但没 record_id
        assertNull(GachaUrlParser.parse("player_id=802104525&svr_id=abc"))
        // 有 record_id 但没 player_id
        assertNull(GachaUrlParser.parse("record_id=dead&svr_id=abc"))
    }

    @Test
    fun `缺少 lang 时用默认值`() {
        // 实测缺 languageCode 接口会返回空数据，所以必须补上一个
        val p = GachaUrlParser.parse("player_id=802104525&record_id=a&resources_id=b&svr_id=c")!!
        assertEquals("zh-Hans", p.languageCode)
    }

    // -------------------------------------------------- 放宽后的解析（只认 record_id）

    @Test
    fun `缺 resources_id 和 svr_id 也能解析`() {
        // resources_id 接口根本不读；svr_id 可以由 playerId 首位推出来。
        // 所以链接被截断、只剩半截参数串时照样能用。
        val bare = GachaUrlParser.parse("player_id=802104525&record_id=dead")!!
        assertEquals("", bare.cardPoolId)
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", bare.serverId)

        val noSvr = GachaUrlParser.parse("player_id=802104525&record_id=dead&resources_id=beef")!!
        assertEquals("beef", noSvr.cardPoolId)
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", noSvr.serverId)
    }

    @Test
    fun `只有 record_id 时用 UID 补齐其余参数`() {
        val p = GachaUrlParser.parseLoose(fakeRecordId, "802104525")!!
        assertEquals("802104525", p.playerId)
        assertEquals(fakeRecordId, p.recordId)
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", p.serverId)
        assertEquals("", p.cardPoolId)
        assertEquals("zh-Hans", p.languageCode)
        assertNull(p.serverArea)
    }

    @Test
    fun `record_id 上的引号空格大小写都能忍`() {
        val want = fakeRecordId
        val messy = listOf(
            "  $want  ",
            "\"$want\"",
            "[$want]",
            want.uppercase(),
            // 复制链接时常见的空格插入
            "01234567 89abcdef 01234567 89abcdef",
        )
        for (raw in messy) {
            assertEquals("输入：$raw", want, GachaUrlParser.parseLoose(raw, "802104525")?.recordId)
        }
    }

    @Test
    fun `没有 UID 时只有 record_id 就解析不出来`() {
        assertNull(GachaUrlParser.parseLoose(fakeRecordId, null))
        assertNull(GachaUrlParser.parseLoose(fakeRecordId, "   "))
        // 认不出来时要能给出「还缺 UID」这种更准确的提示
        assertTrue(GachaUrlParser.looksLikeBareRecordId(fakeRecordId))
        assertTrue(GachaUrlParser.looksLikeBareRecordId("  \"$fakeRecordId\" "))
        assertTrue(!GachaUrlParser.looksLikeBareRecordId("b4393904"))
        assertTrue(!GachaUrlParser.looksLikeBareRecordId("随便一段文字"))
    }

    @Test
    fun `完整链接优先于 fallback UID`() {
        val p = GachaUrlParser.parseLoose(realUrl, "999999999")!!
        assertEquals("802104525", p.playerId)
        assertEquals(fakeRecordId, p.recordId)
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", p.serverId)
    }

    // ------------------------------------------------------------ 区服对照表

    @Test
    fun `playerId 首位决定区服`() {
        // 表来自 wuwatracker 前端；8 -> 919752 与我们链接里的 svr_id 完全一致
        assertEquals("919752ae5ea09c1ced910dd668a63ffb", GachaServer.serverIdFor("802104525"))
        assertEquals("76402e5b20be2c39f095a152090afddc", GachaServer.serverIdFor("102104525"))
        assertEquals("591d6af3a3090d8ea00d8f86cf6d7501", GachaServer.serverIdFor("502104525"))
        assertEquals("6eb2a235b30d05efd77bedb5cf60999e", GachaServer.serverIdFor("602104525"))
        assertEquals("86d52186155b148b5c138ceb41be9650", GachaServer.serverIdFor("702104525"))
        assertEquals("10cd7254d57e58ae560b15d51e34b4c8", GachaServer.serverIdFor("902104525"))
        // 没见过的开头交给兜底逻辑去挨个试
        assertNull(GachaServer.serverIdFor("302104525"))
        assertNull(GachaServer.serverIdFor(""))
    }

    // ------------------------------------------------------------ 端点选择

    private fun params(playerId: String, area: String?) =
        GachaParams(playerId, "r", "c", "s", "zh-Hans", area)

    @Test
    fun `识别国际服与国服端点`() {
        assertTrue(GachaApi.endpointFor(params("802104525", "global")).contains("aki-game2.net"))
        assertTrue(GachaApi.endpointFor(params("802104525", null)).contains("aki-game2.net"))
        assertTrue(GachaApi.endpointFor(params("102104525", null)).contains("aki-game2.com"))
        assertTrue(GachaApi.endpointFor(params("102104525", "cn")).contains("aki-game2.com"))
    }

    @Test
    fun `svr_area 比 playerId 前缀更优先`() {
        // 万一以后国服 playerId 不以 1 开头，svr_area 仍然能给出正确答案
        assertTrue(GachaApi.endpointFor(params("902104525", "global")).contains("aki-game2.net"))
        assertTrue(GachaApi.endpointFor(params("902104525", "cn")).contains("aki-game2.com"))
    }

    // ------------------------------------------------------ 反向：拼出完整链接

    /** 拼出来的链接必须能被自己的解析器读回来，这是最有说服力的一条。 */
    @Test
    fun `只有 record_id 时能拼出完整链接并且能被解析回去`() {
        val original = GachaParams(
            playerId = "802104525",
            recordId = fakeRecordId,
            serverId = "919752ae5ea09c1ced910dd668a63ffb",
        )
        val url = GachaUrlBuilder.build(original)

        val parsed = GachaUrlParser.parse(url)
        assertTrue("拼出来的链接必须能被自己解析", parsed != null)
        assertEquals(original.playerId, parsed!!.playerId)
        assertEquals(original.recordId, parsed.recordId)
        assertEquals(original.serverId, parsed.serverId)
        assertEquals("zh-Hans", parsed.languageCode)
        assertEquals("global", parsed.serverArea)
    }

    @Test
    fun `拼出的链接带上别的工具要读的字段`() {
        val url = GachaUrlBuilder.build(
            GachaParams("802104525", fakeRecordId, serverId = "919752ae5ea09c1ced910dd668a63ffb")
        )
        // 桌面版 GachaLogUtil.getParamFromUrl 认这六个，缺一个就少一个参数
        for (key in listOf("player_id", "record_id", "resources_id", "gacha_type", "svr_id", "lang")) {
            assertTrue("链接里少了 $key：$url", url.contains("$key="))
        }
        assertTrue("用的是国际服记录页", url.startsWith("https://aki-gm-resources-oversea.aki-game.net/"))
        assertTrue("带上 #/record 标记", url.contains("#/record?"))
    }

    @Test
    fun `区服为空时由 playerId 首位推出来`() {
        val url = GachaUrlBuilder.build(GachaParams("802104525", fakeRecordId))
        assertTrue(url.contains("svr_id=919752ae5ea09c1ced910dd668a63ffb"))
    }

    @Test
    fun `有 resources_id 就用真的，没有才补占位值`() {
        val withReal = GachaUrlBuilder.build(
            GachaParams("802104525", fakeRecordId, cardPoolId = "beefbeefbeefbeefbeefbeefbeefbeef")
        )
        assertTrue(withReal.contains("resources_id=beefbeefbeefbeefbeefbeefbeefbeef"))

        val placeholder = GachaUrlBuilder.build(GachaParams("802104525", fakeRecordId))
        val guess = Regex("""resources_id=([^&]+)""").find(placeholder)!!.groupValues[1]
        assertEquals("占位值也要是个像样的 32 位十六进制", 32, guess.length)
    }

    @Test
    fun `国服账号拼出国服记录页`() {
        val url = GachaUrlBuilder.build(GachaParams("102104525", fakeRecordId, serverArea = "cn"))
        assertTrue(url.contains("svr_area=cn"))
        assertTrue(url.startsWith("https://aki-gm-resources.aki-game.com/"))
    }
}
