package com.inmc.economy.currency

import com.inmc.economy.Eco
import kr.inmc.core.economy.Currencies
import kr.inmc.core.economy.Currency
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * core 가 보는 화폐 하나. 정의는 GUI 로 바뀌므로 들고 있지 않고 **부를 때마다 id 로 찾는다** — 옛 정의를 붙들면
 * 관리자가 고친 한도·단위가 반영되지 않는다. 정의가 지워졌으면 잔고 0, 주고받기는 거절.
 */
class InmcCurrency(private val eco: Eco, override val id: String) : Currency {

    private fun def(): CurrencyDef? = eco.currencies.get(id)

    override val name: String get() = def()?.name ?: id

    override fun format(amount: Long): String = def()?.format(amount) ?: "%,d".format(amount)

    override fun balance(player: OfflinePlayer): Long {
        val def = def() ?: return 0
        return if (def.inventory) eco.items.count(player, def) else eco.accounts.balance(player.uniqueId, def)
    }

    override fun withdraw(player: OfflinePlayer, amount: Long, reason: String): Boolean {
        if (amount <= 0) return true
        val def = def() ?: return false
        return if (def.inventory) eco.items.take(player, def, amount, reason) else eco.accounts.add(player.uniqueId, def, -amount, reason) != null
    }

    override fun deposit(player: OfflinePlayer, amount: Long, reason: String): Boolean {
        if (amount <= 0) return true
        val def = def() ?: return false
        return if (def.inventory) eco.items.give(player, def, amount, reason) else eco.accounts.add(player.uniqueId, def, amount, reason) != null
    }
}

/** core 의 [Currencies] 에 꽂는 공급처. */
class CurrencyProvider(private val eco: Eco) : Currencies.Provider {

    private val wrappers = ConcurrentHashMap<String, InmcCurrency>()

    private fun of(id: String): InmcCurrency = wrappers.getOrPut(id) { InmcCurrency(eco, id) }

    override fun default(): Currency? = eco.currencies.default()?.let { of(it.id) }

    override fun get(id: String): Currency? = eco.currencies.get(id)?.let { of(it.id) }

    override fun all(): List<Currency> = eco.currencies.all().map { of(it.id) }
}

/**
 * 순위. 잔고는 전부 메모리에 있어 정렬만 하면 되지만, TAB 같은 것이 초마다 물으므로 몇 초에 한 번만 다시 센다.
 * 가방 속 개수형은 접속하지 않은 사람의 잔고를 몰라 순위가 없다.
 */
class RankCache(private val eco: Eco) {

    data class Entry(val player: UUID, val amount: Long)

    @Volatile
    private var tables: Map<String, List<Entry>> = emptyMap()

    fun top(currency: String): List<Entry> = tables[currency].orEmpty()

    /** [player] 의 순위(1부터). 순위에 없으면 null. */
    fun position(currency: String, player: UUID): Int? = top(currency).indexOfFirst { it.player == player }.takeIf { it >= 0 }?.plus(1)

    fun refresh() {
        tables = eco.currencies.all()
            .filter { it.ranked && !it.inventory }
            .associate { def ->
                def.id to eco.accounts.holders(def.id).entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }
                    .take(LIMIT)
                    .map { Entry(it.key, it.value) }
            }
    }

    companion object {
        const val LIMIT = 100
    }
}
