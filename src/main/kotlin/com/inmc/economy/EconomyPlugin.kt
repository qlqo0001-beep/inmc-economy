package com.inmc.economy

import com.inmc.economy.command.EconomyCommand
import com.inmc.economy.config.EconomyConfig
import com.inmc.economy.config.Messages
import com.inmc.economy.currency.CurrencyRegistry
import com.inmc.economy.currency.Scope
import com.inmc.economy.hook.PapiHook
import com.inmc.economy.hook.VaultEconomy
import com.inmc.economy.listener.EconomyListener
import com.inmc.economy.scheduler.Ticker
import com.inmc.economy.store.Db
import kr.inmc.core.economy.Currencies
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * 켜질 때는 **전부 그 자리에서 읽는다**(설정·화폐·잔고). Vault 를 쓰는 플러그인은 우리 바로 뒤에 켜지면서 곧장 잔고를
 * 묻는데, 비동기로 읽어 한 틱 늦으면 그 사이의 답이 전부 틀린다(아무도 돈이 없다). 서버가 틱을 돌기 전이라 괜찮다.
 */
class EconomyPlugin : JavaPlugin() {

    private lateinit var eco: Eco
    private lateinit var ticker: Ticker
    private lateinit var papi: PapiHook
    private var vault: VaultEconomy? = null

    override fun onEnable() {
        eco = Eco(this)
        for (name in RESOURCES) eco.io.copyDefault(name, eco.io.file(name))
        eco.config = EconomyConfig.from(eco.io.load(eco.io.file("config.yml")))
        eco.messages = Messages.from(eco.io.load(eco.io.file("messages.yml")))
        eco.currencies.loadNow()
        eco.customItems.setup()
        eco.mmoItems.setup()

        val db = Db(logger, eco.config.serverName)
        try {
            db.open(File(dataFolder, "economy.db"), eco.config.networkUrl, eco.config.networkUser, eco.config.networkPassword)
        } catch (t: Throwable) {
            logger.severe("DB 를 열지 못했습니다 - 화폐를 끕니다: ${t.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        eco.openStorage(db)
        loadBalances()

        // 공급처는 다른 플러그인이 묻기 전에 꽂는다.
        Currencies.register(eco.provider)
        vault = VaultEconomy(eco).also { server.servicesManager.register(Economy::class.java, it, this, ServicePriority.Highest) }

        server.pluginManager.registerEvents(EconomyListener(eco), this)
        server.pluginManager.registerEvents(kr.inmc.core.listener.MenuListener(eco), this)
        papi = PapiHook(eco).also {
            server.pluginManager.registerEvents(it, this)
            it.setup()
        }
        EconomyCommand(eco, this).register(this)

        // 커스텀아이템에 "실물 화폐·수표 모양" 역할을 내놓는다(core ItemRoles).
        for (role in com.inmc.economy.currency.EconomyRoles.roles(eco)) kr.inmc.core.integration.ItemRoles.register(role)
        kr.inmc.core.integration.ItemRoles.listen(com.inmc.economy.currency.EconomyRoles.OWNER) { role ->
            if (role == null || role == com.inmc.economy.currency.EconomyRoles.CURRENCY || role == com.inmc.economy.currency.EconomyRoles.CHEQUE) {
                com.inmc.economy.currency.EconomyRoles.sync(eco)
            }
        }
        com.inmc.economy.currency.EconomyRoles.sync(eco)

        ticker = Ticker(eco)
        eco.markReady()
        ticker.start()
        val def = eco.currencies.default()
        logger.info("inmc-economy 활성화 완료 - 화폐 ${eco.currencies.all().size}개, 기본 ${def?.id ?: "없음"}" +
            (if (db.network != null) " · 통합 화폐 공용 DB 연결" else ""))
    }

    override fun onDisable() {
        if (!::eco.isInitialized) return
        if (::ticker.isInitialized) ticker.stop()
        if (::papi.isInitialized) papi.teardown()
        Currencies.unregister(eco.provider)
        kr.inmc.core.integration.ItemRoles.unregisterAll(com.inmc.economy.currency.EconomyRoles.OWNER)
        vault?.let { server.servicesManager.unregister(Economy::class.java, it) }
        eco.currencies.saveIfDirty()
        // 줄 선 잔고 변경을 다 쓰고 닫는다.
        runCatching { eco.db.shutdown() }
        eco.io.shutdown()
    }

    /** 잔고 전부. 공용 DB 가 있으면 통합 화폐는 거기서, 나머지는 이 서버의 SQLite 에서. */
    private fun loadBalances() {
        val db = eco.db
        val network = eco.currencies.all().filter { it.scope == Scope.NETWORK }.map { it.id }.toSet()
        val rows = db.call {
            val local = db.balances(db.local)
            val shared = db.network?.let { db.balances(it) }
            if (shared == null) local else local.filter { it.currency !in network } + shared.filter { it.currency in network }
        }
        eco.accounts.load(rows)
    }

    /** `/돈 리로드`. 파일은 워커에서 읽고 반영은 메인에서. 잔고는 다시 읽지 않는다(메모리가 진짜다). */
    fun reload(then: () -> Unit) {
        // 화면에서 고친 것이 아직 안 써졌으면 먼저 쓴다 — 안 그러면 파일에서 다시 읽으며 사라진다.
        eco.currencies.saveIfDirty()
        eco.io.async({
            Triple(eco.io.load(eco.io.file("config.yml")), eco.io.load(eco.io.file("messages.yml")), eco.io.load(eco.io.file(CurrencyRegistry.FILE)))
        }) { (config, messages, currencies) ->
            eco.config = EconomyConfig.from(config)
            eco.messages = Messages.from(messages)
            eco.currencies.reloadFrom(currencies)
            closeMenus()
            ticker.start()
            then()
        }
    }

    private fun closeMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder is kr.inmc.core.gui.Menu && holder.owner === eco) player.closeInventory()
        }
    }

    private companion object {
        val RESOURCES = listOf("config.yml", "messages.yml", CurrencyRegistry.FILE)
    }
}
