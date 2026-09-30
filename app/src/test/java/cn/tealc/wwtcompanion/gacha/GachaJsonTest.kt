package cn.tealc.wwtcompanion.gacha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GachaJsonTest {

    private val desktopPool = """
    {
      "角色活动唤取" : [ {
        "cardPoolType" : "角色精准调谐",
        "resourceId" : 1108,
        "qualityLevel" : 5,
        "resourceType" : "角色",
        "name" : "绯雪",
        "count" : 1,
        "time" : "2026-09-29 00:23:06"
      }, {
        "cardPoolType" : "角色精准调谐",
        "resourceId" : 21040013,
        "qualityLevel" : 3,
        "resourceType" : "武器",
        "name" : "暗夜臂铠·夜芒",
        "count" : 1,
        "time" : "2026-09-28 12:00:00"
      } ],
      "武器活动唤取" : [ {
        "cardPoolType" : "武器精准调谐",
        "resourceId" : 21020086,
        "qualityLevel" : 5,
        "resourceType" : "武器",
        "name" : "灼霜",
        "count" : 1,
        "time" : "2026-09-23 19:17:02"
      } ],
      "角色常驻唤取" : [ ]
    }
    """.trimIndent()

    @Test
    fun `解析桌面版 pool_json`() {
        val r = GachaJson.parseDesktopPool(desktopPool)
        assertEquals(3, r.records.size)
        assertTrue(r.unknownPools.isEmpty())

        val event = r.records.filter { it.poolId == GachaPool.CHARACTER_EVENT.id }
        assertEquals(2, event.size)
        assertEquals(GachaPool.WEAPON_EVENT.id, r.records.first { it.name == "灼霜" }.poolId)
    }

    @Test
    fun `每个卡池内按时间倒序`() {
        val r = GachaJson.parseDesktopPool(desktopPool)
        val event = r.records.filter { it.poolId == GachaPool.CHARACTER_EVENT.id }
        assertEquals("绯雪", event.first().name) // 09-29 比 09-28 新
        assertEquals("暗夜臂铠·夜芒", event.last().name)
    }

    @Test
    fun `空卡池不会产生记录`() {
        val r = GachaJson.parseDesktopPool(desktopPool)
        assertTrue(r.records.none { it.poolId == GachaPool.CHARACTER_STANDARD.id })
    }

    @Test
    fun `认不出的卡池名会被报出来而不是静默丢掉`() {
        val json = """
        { "某个未来的新卡池" : [ {
            "resourceId" : 1, "qualityLevel" : 3, "resourceType" : "武器",
            "name" : "x", "count" : 1, "time" : "2026-01-01 00:00:00"
        } ] }
        """.trimIndent()
        val r = GachaJson.parseDesktopPool(json)
        assertEquals(listOf("某个未来的新卡池"), r.unknownPools)
        assertTrue(r.records.isEmpty())
    }

    @Test
    fun `字段缺失的记录会被跳过`() {
        val json = """
        { "新手唤取" : [
          { "resourceId" : 1, "qualityLevel" : 3, "name" : "有名字没时间", "count" : 1 },
          { "resourceId" : 2, "qualityLevel" : 3, "time" : "2026-01-01 00:00:00" },
          { "resourceId" : 3, "qualityLevel" : 3, "name" : "好记录", "time" : "2026-01-01 00:00:01" }
        ] }
        """.trimIndent()
        val r = GachaJson.parseDesktopPool(json)
        assertEquals(1, r.records.size)
        assertEquals("好记录", r.records.first().name)
    }

    @Test
    fun `存档编码解码可以往返`() {
        val original = GachaJson.parseDesktopPool(desktopPool).records
        val restored = GachaJson.decode(GachaJson.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `同一时刻的重复记录在往返后仍然保留`() {
        val original = listOf(
            GachaRecord(1, "调谐", 7, 3, "武器", "同名", 1, "2026-01-01 00:00:09"),
            GachaRecord(1, "调谐", 7, 3, "武器", "同名", 1, "2026-01-01 00:00:09"),
        )
        assertEquals(original, GachaJson.decode(GachaJson.encode(original)))
    }

    @Test
    fun `空存档解码为空列表`() {
        assertTrue(GachaJson.decode(GachaJson.encode(emptyList())).isEmpty())
    }

    @Test
    fun `count 至少为一`() {
        val json = """
        { "新手唤取" : [ {
            "resourceId" : 1, "qualityLevel" : 3, "name" : "x",
            "count" : 0, "time" : "2026-01-01 00:00:00"
        } ] }
        """.trimIndent()
        assertEquals(1, GachaJson.parseDesktopPool(json).records.first().count)
    }
}
