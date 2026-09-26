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

    /** `<화폐>_<n>_name|amount|formatted`. 화폐 id 에 밑줄이 들어갈 수 있어 뒤에서부터 자른다. */
    private fun top(rest: String): String {
        val parts = rest.split('_')
        if (parts.size < 3) return ""
        val field = parts.last()
        val position = parts[parts.size - 2].toIntOrNull() ?: return ""
        val def = eco.currency(parts.dropLast(2).joinToString("_")) ?: return ""
        val entry = eco.ranks.top(def.id).getOrNull(position - 1) ?: return ""
        return when (field) {
            "name" -> eco.nameOf(entry.player)
            "amount" -> entry.amount.toString()
            "formatted" -> def.format(entry.amount)
            else -> ""
        }
    }
}
