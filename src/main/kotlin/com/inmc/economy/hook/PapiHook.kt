package com.inmc.economy.hook

import com.inmc.economy.Eco
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent

/**
 * `%inmceco_…%`. PlaceholderAPI 는 compileOnly 라 **켜져 있을 때만** 건드린다.
 * 우리보다 늦게 켜지면 그때 붙는다([onEnable]).
 */
class PapiHook(private val eco: Eco) : Listener {

    private var expansion: PlaceholderExpansion? = null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return
        try {
            expansion = EcoExpansion(eco).also { it.register() }
            eco.logger.info("PlaceholderAPI 연동 활성화 (%inmceco_...%)")
        } catch (t: Throwable) {
            eco.logger.warning("PlaceholderAPI 연동 실패: ${t.message}")
            teardown()
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
    }

    @EventHandler
    fun onEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "PlaceholderAPI" && expansion == null) setup()
    }
}

/**
 * 따로 둔 것은 PlaceholderAPI 가 있을 때만 이 클래스가 적재되게 하려는 것이다.
 *
 * ```
 * %inmceco_balance%              기본 화폐 잔고(숫자)        %inmceco_balance_<화폐>%
 * %inmceco_formatted%            기본 화폐 잔고("1,000원")   %inmceco_formatted_<화폐>%
 * %inmceco_rank%                 기본 화폐 순위              %inmceco_rank_<화폐>%
 * %inmceco_top_<화폐>_<n>_name%  n위 이름   _amount  n위 잔고(숫자)   _formatted  n위 잔고("1,000원")
 * ```
 */
private class EcoExpansion(private val eco: Eco) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "inmceco"

    override fun getAuthor(): String = "INMC"

    override fun getVersion(): String = eco.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    /**
     * PAPI 의 목록(`/papi info inmceco`) — 고정 이름과 지금 있는 화폐로 펼친 이름(순위는 1~10위). [onRequest] 의 키와 같아야 한다.
     * 순위·top 은 순위표가 있는 화폐(`ranked` 이고 가방 속 개수형이 아닌 것 — [com.inmc.economy.currency.RankCache.refresh])만.
     */
    override fun getPlaceholders(): List<String> {
        val default = eco.currencies.default()
        val keys = EcoPlaceholders.keys(
            eco.currencies.all().map { it.id to (it.ranked && !it.inventory) },
            default = default?.let { it.ranked && !it.inventory },
        )
        return keys.map { "%inmceco_$it%" }
    }

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val lower = params.lowercase()
        if (lower.startsWith("top_")) return top(lower.removePrefix("top_"))
        val who = player ?: return ""
        return when {
            lower == "balance" -> amount(who, null)?.toString()
            lower == "formatted" -> formatted(who, null)
            lower == "rank" -> rank(who, null)
            lower.startsWith("balance_") -> amount(who, lower.removePrefix("balance_"))?.toString()
            lower.startsWith("formatted_") -> formatted(who, lower.removePrefix("formatted_"))
            lower.startsWith("rank_") -> rank(who, lower.removePrefix("rank_"))
            else -> null
        }
    }

    private fun amount(player: OfflinePlayer, currency: String?): Long? {
        val def = eco.currency(currency) ?: return null
        return if (def.inventory) eco.items.count(player, def) else eco.accounts.balance(player.uniqueId, def)
    }

    private fun formatted(player: OfflinePlayer, currency: String?): String? {
        val def = eco.currency(currency) ?: return null
        return def.format(amount(player, currency) ?: 0)
    }

    private fun rank(player: OfflinePlayer, currency: String?): String {
        val def = eco.currency(currency) ?: return ""
        return eco.ranks.position(def.id, player.uniqueId)?.toString() ?: ""
    }

    /** `<화폐>_<n>_name|amount|formatted`. */
    private fun top(rest: String): String {
        val (currency, position, field) = EcoPlaceholders.top(rest) ?: return ""
        val def = eco.currency(currency) ?: return ""
        val entry = eco.ranks.top(def.id).getOrNull(position - 1) ?: return ""
        return when (field) {
            "name" -> eco.nameOf(entry.player)
            "amount" -> entry.amount.toString()
            "formatted" -> def.format(entry.amount)
            else -> ""
        }
    }
}

/** 키 목록과 top 키 풀기 — PlaceholderAPI 없이 시험하려고([EcoExpansion] 은 PAPI 가 있어야 적재된다) 밖에 둔다. */
internal object EcoPlaceholders {

    const val TOP_SIZE = 10

    /**
     * `%inmceco_` 와 `%` 를 뺀 키들. [currencies] 는 (화폐 id, 순위표가 있나). [default] 는 기본 화폐의 순위표 유무 — 기본 화폐가 없으면 null
     * (그때 `balance`·`formatted`·`rank` 는 빈 값이라 싣지 않는다).
     */
    fun keys(currencies: List<Pair<String, Boolean>>, default: Boolean?): List<String> {
        val keys = mutableListOf<String>()
        if (default != null) {
            keys += listOf("balance", "formatted")
            if (default) keys += "rank"
        }
        for ((raw, ranked) in currencies) {
            val id = raw.lowercase()
            keys += listOf("balance_$id", "formatted_$id")
            if (!ranked) continue
            keys += "rank_$id"
            for (n in 1..TOP_SIZE) keys += listOf("top_${id}_${n}_name", "top_${id}_${n}_amount", "top_${id}_${n}_formatted")
        }
        return keys
    }

    /** `top_` 뒤의 `<화폐>_<n>_<칸>` 을 (화폐, n, 칸)으로. 화폐 id 에 밑줄이 들어갈 수 있어 뒤에서부터 자른다. */
    fun top(rest: String): Triple<String, Int, String>? {
        val parts = rest.split('_')
        if (parts.size < 3) return null
        val position = parts[parts.size - 2].toIntOrNull() ?: return null
        return Triple(parts.dropLast(2).joinToString("_"), position, parts.last())
    }
}
