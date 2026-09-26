package com.inmc.economy.util

import kr.inmc.core.util.TokenBag

/** 메시지 한 번 렌더링에 쓰이는 토큰 주머니. 한글/영문 둘 다 받는다 — 다른 INMC 플러그인들과 같은 관례. */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun currency(name: String): Ph = put(CURRENCY, name)

    /** 형식을 입힌 금액 — "1,000원". */
    fun amount(text: String): Ph = put(AMOUNT, text)

    fun balance(text: String): Ph = put(BALANCE, text)

    fun value(text: String): Ph = put(VALUE, text)

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun copy(): Ph = copyValuesInto(Ph())

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val CURRENCY = "currency"
        const val AMOUNT = "amount"
        const val BALANCE = "balance"
        const val VALUE = "value"
        const val COUNT = "count"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            CURRENCY to listOf("{화폐}", "{currency}"),
            AMOUNT to listOf("{금액}", "{amount}"),
            BALANCE to listOf("{잔고}", "{balance}"),
            VALUE to listOf("{값}", "{value}"),
            COUNT to listOf("{개수}", "{count}"),
        )
    }
}
