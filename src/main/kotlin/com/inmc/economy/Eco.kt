package com.inmc.economy

import com.inmc.economy.config.EconomyConfig
import com.inmc.economy.config.Messages
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.CurrencyRegistry
import com.inmc.economy.util.Ph
import kr.inmc.core.CorePlugin
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.store.Profile
import kr.inmc.core.util.Placeholders
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 플러그인을 엮는 서비스 로케이터. (Vault 의 `Economy` 와 이름이 겹치지 않게 `Eco`.)
 *
 * 이 플러그인은 **다른 플러그인들의 공급처**다 — core 의 [kr.inmc.core.economy.Currencies] 에 화폐를 꽂고, Vault 로도
 * 기본 화폐를 보인다. 보상·가격을 가진 쪽은 이 플러그인을 모른다.
 */
class Eco(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) = messages.send(target, key, ph as? Ph)

    @Volatile
    var config: EconomyConfig = EconomyConfig()

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    val currencies = CurrencyRegistry(io)

    lateinit var db: com.inmc.economy.store.Db
        private set

    lateinit var accounts: com.inmc.economy.store.Accounts
        private set

    fun openStorage(db: com.inmc.economy.store.Db) {
        this.db = db
        this.accounts = com.inmc.economy.store.Accounts(db)
    }

    val customItems = CustomItemHook(logger)

    val mmoItems = MMOItemsHook(logger)

    /** 실물 화폐의 아이템을 만들고 알아본다(바닐라·모델 번호·커스텀아이템·MMOItems). */
    val resolver = ItemResolver(mmoItems, customItems, logger)

    val matcher = ItemMatcher(mmoItems, customItems)

    /** 실물 화폐 — 가방 속 개수·입금·출금. */
    val items = com.inmc.economy.currency.ItemCurrencies(this)

    /** core 에 꽂는 공급처. */
    val provider = com.inmc.economy.currency.CurrencyProvider(this)

    val cheques = com.inmc.economy.cheque.ChequeService(this)

    /** 송금·관리자 지급 — 명령어와 화면이 같이 쓴다. */
    val transfers = com.inmc.economy.currency.Transfers(this)

    val ranks = com.inmc.economy.currency.RankCache(this)

    val prompts = ChatPrompt(this)

    /** 비었으면 기본 화폐. */
    fun currency(id: String?): CurrencyDef? = if (id.isNullOrBlank()) currencies.default() else currencies.get(id)

    /** 보이는 이름 — 접속 중이면 그 이름, 아니면 core 가 기억하는 이름. */
    fun nameOf(player: UUID): String =
        Bukkit.getPlayer(player)?.name
            ?: runCatching { Profile.nameOf(CorePlugin.get().players, player) }.getOrNull()
            ?: Bukkit.getOfflinePlayer(player).name
            ?: player.toString().take(8)

    /** 이름으로 찾기 — 접속 중인 사람, core 가 기억하는 사람, 서버가 기억하는 사람 순. 없으면 null(웹 조회는 안 한다). */
    fun findPlayer(name: String): UUID? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        val store = runCatching { CorePlugin.get().players }.getOrNull()
        if (store != null) {
            for (id in store.knownPlayers()) if (Profile.nameOf(store, id).equals(name, ignoreCase = true)) return id
        }
        return Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
    }

    @Volatile
    var ready: Boolean = false
        private set

    fun markReady() {
        ready = true
    }
}
