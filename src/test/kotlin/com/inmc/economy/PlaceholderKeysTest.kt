package com.inmc.economy

import com.inmc.economy.hook.EcoPlaceholders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/papi info inmceco` 의 목록([EcoPlaceholders.keys])이 `onRequest` 가 푸는 키만 싣는지.
 * 순위·top 은 순위표가 있는 화폐(ranked 이고 가방 속 개수형이 아닌 것)만 — 아니면 영영 빈 칸이다.
 */
class PlaceholderKeysTest {

    /** `onRequest` 의 갈래 — 정확히 같은 키 셋과 앞머리 넷. */
    private val exact = setOf("balance", "formatted", "rank")
    private val prefixes = listOf("balance_", "formatted_", "rank_", "top_")

    private val keys = EcoPlaceholders.keys(listOf("money" to true, "Gold_Coin" to false, "cash" to true), default = true)

    @Test
    fun `모든 키가 onRequest 의 갈래에 걸린다`() {
        for (key in keys) assertTrue(key in exact || prefixes.any { key.startsWith(it) }, key)
    }

    @Test
    fun `top 키는 화폐·순위·칸으로 되풀린다`() {
        val tops = keys.filter { it.startsWith("top_") }
        assertEquals(2 * EcoPlaceholders.TOP_SIZE * 3, tops.size)
        for (key in tops) {
            val (currency, position, field) = EcoPlaceholders.top(key.removePrefix("top_"))!!
            assertTrue(currency in setOf("money", "cash"), key)
            assertTrue(position in 1..EcoPlaceholders.TOP_SIZE, key)
            assertTrue(field in setOf("name", "amount", "formatted"), key)
        }
        // 밑줄 든 화폐 id 도 뒤에서부터 자른다.
        assertEquals(Triple("gold_coin", 3, "name"), EcoPlaceholders.top("gold_coin_3_name"))
    }

    @Test
    fun `순위표 없는 화폐는 잔고만`() {
        assertTrue("balance_gold_coin" in keys)
        assertTrue("formatted_gold_coin" in keys)
        assertFalse(keys.any { it == "rank_gold_coin" || it.startsWith("top_gold_coin_") })
    }

    @Test
    fun `기본 화폐에 따라 고정 키`() {
        assertEquals(listOf("balance", "formatted", "rank"), EcoPlaceholders.keys(emptyList(), default = true))
        assertEquals(listOf("balance", "formatted"), EcoPlaceholders.keys(emptyList(), default = false))
        assertEquals(emptyList(), EcoPlaceholders.keys(emptyList(), default = null))
    }
}
