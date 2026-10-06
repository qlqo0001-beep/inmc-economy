package com.inmc.economy

import com.inmc.economy.config.EconomyConfig
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 수수료 계산 + `/돈` 명령어 형태(리터럴 vs 플레이어 인자).
 *
 * 수수료는 올림이라 소액도 최소 1원이다. 명령어는 `/돈 메뉴` 가 "메뉴"라는
 * 플레이어를 찾지 않고 지갑으로 가야 한다 — Brigadier 는 같은 입력에 맞는 자식이
 * 여럿이면 마지막에 맞은 것을 쓰므로, 인자를 리터럴보다 먼저 둔다.
 */
class FeesTest {

    @Test
    fun `꺼져 있으면 0원`() {
        assertEquals(0L, EconomyConfig.feeFor(10000L, false, 5.0))
        assertEquals(0L, EconomyConfig.feeFor(10000L, true, 0.0))
        assertEquals(0L, EconomyConfig.feeFor(0L, true, 5.0))
    }

    @Test
    fun `기본 5퍼센트`() {
        assertEquals(5L, EconomyConfig.feeFor(100L, true, 5.0))
        assertEquals(50L, EconomyConfig.feeFor(1000L, true, 5.0))
    }

    @Test
    fun `올림이라 1원에도 1원`() {
        assertEquals(1L, EconomyConfig.feeFor(1L, true, 5.0))
        assertEquals(1L, EconomyConfig.feeFor(19L, true, 5.0))
        assertEquals(1L, EconomyConfig.feeFor(20L, true, 5.0))
    }

    @Test
    fun `설정 기본값은 켜짐·5퍼센트`() {
        val config = EconomyConfig()
        assertEquals(true, config.feeEnabled)
        assertEquals(5.0, config.feePercent)
        assertEquals(5L, config.feeFor(100L))
    }

    // --- 명령어 형태 ---------------------------------------------------------

    private fun tree(orderArgFirst: Boolean): LiteralArgumentBuilder<String> {
        val arg: RequiredArgumentBuilder<String, String> =
            com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("플레이어", StringArgumentType.word())
        val menu = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<String>("메뉴")
        val root = com.mojang.brigadier.builder.LiteralArgumentBuilder.literal<String>("돈")
        if (orderArgFirst) {
            root.then(arg)
            root.then(menu)
        } else {
            root.then(menu)
            root.then(arg)
        }
        return root
    }

    private fun parseLeaf(orderArgFirst: Boolean, input: String): String {
        val dispatcher = CommandDispatcher<String>()
        dispatcher.register(tree(orderArgFirst))
        val parse = dispatcher.parse(input, "console")
        return parse.context.nodes.lastOrNull()?.node?.name ?: "(없음)"
    }

    @Test
    fun `인자가 먼저면 리터럴이 이긴다`() {
        assertEquals("메뉴", parseLeaf(orderArgFirst = true, input = "돈 메뉴"))
        assertEquals("플레이어", parseLeaf(orderArgFirst = true, input = "돈 Steve"))
    }
}
