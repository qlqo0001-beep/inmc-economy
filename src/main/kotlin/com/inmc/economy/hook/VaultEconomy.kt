package com.inmc.economy.hook

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import kotlin.math.roundToLong

/**
 * Vault 로 보이는 **기본 화폐**. 우리가 만들지 않은 플러그인(상점·땅·채팅…)이 이것으로 돈을 주고받는다.
 *
 * - 금액은 정수라 Vault 의 Double 을 반올림한다(`fractionalDigits() == 0`).
 * - 월드 인자는 무시한다 — 월드별 잔고는 없다. 서버별 잔고는 화폐의 scope 로 한다.
 * - 은행(Vault 의 bank)은 없다.
 * - 이름으로 묻는 옛 방식은 접속 중·기억하는 사람만 찾는다(모르는 이름으로 계정을 만들지 않는다).
 */
class VaultEconomy(private val eco: Eco) : Economy {

    private fun def(): CurrencyDef? = eco.currencies.default()

    private fun whole(amount: Double): Long = amount.roundToLong()

    private fun byName(name: String?): OfflinePlayer? = name?.let(eco::findPlayer)?.let(Bukkit::getOfflinePlayer)

    override fun isEnabled(): Boolean = eco.ready

    override fun getName(): String = "inmc-economy"

    override fun hasBankSupport(): Boolean = false

    override fun fractionalDigits(): Int = 0

    override fun format(amount: Double): String = def()?.format(whole(amount)) ?: "%,d".format(whole(amount))

    override fun currencyNamePlural(): String = def()?.unit ?: ""

    override fun currencyNameSingular(): String = def()?.unit ?: ""

    // --- 계정 -------------------------------------------------------------------------------

    /** 잔고는 누구에게나 있다(처음이면 시작 금액) — 계정을 따로 만들 필요가 없다. */
    override fun hasAccount(player: OfflinePlayer): Boolean = true

    override fun hasAccount(player: OfflinePlayer, world: String?): Boolean = true

    override fun hasAccount(name: String): Boolean = byName(name) != null

    override fun hasAccount(name: String, world: String?): Boolean = hasAccount(name)

    override fun createPlayerAccount(player: OfflinePlayer): Boolean = true

    override fun createPlayerAccount(player: OfflinePlayer, world: String?): Boolean = true

    override fun createPlayerAccount(name: String): Boolean = byName(name) != null

    override fun createPlayerAccount(name: String, world: String?): Boolean = createPlayerAccount(name)

    // --- 잔고 -------------------------------------------------------------------------------

    override fun getBalance(player: OfflinePlayer): Double {
        val def = def() ?: return 0.0
        return eco.accounts.balance(player.uniqueId, def).toDouble()
    }

    override fun getBalance(player: OfflinePlayer, world: String?): Double = getBalance(player)

    override fun getBalance(name: String): Double = byName(name)?.let(::getBalance) ?: 0.0

    override fun getBalance(name: String, world: String?): Double = getBalance(name)

    override fun has(player: OfflinePlayer, amount: Double): Boolean = amount <= 0.0 || getBalance(player) >= whole(amount)

    override fun has(player: OfflinePlayer, world: String?, amount: Double): Boolean = has(player, amount)

    override fun has(name: String, amount: Double): Boolean = byName(name)?.let { has(it, amount) } ?: false

    override fun has(name: String, world: String?, amount: Double): Boolean = has(name, amount)

    // --- 주고받기 ----------------------------------------------------------------------------

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        if (amount < 0.0) failure(amount, "음수 금액은 뺄 수 없습니다") else change(player, -whole(amount), "vault:withdraw")

    override fun withdrawPlayer(player: OfflinePlayer, world: String?, amount: Double): EconomyResponse = withdrawPlayer(player, amount)

    override fun withdrawPlayer(name: String, amount: Double): EconomyResponse =
        byName(name)?.let { withdrawPlayer(it, amount) } ?: failure(amount, "모르는 플레이어: $name")

    override fun withdrawPlayer(name: String, world: String?, amount: Double): EconomyResponse = withdrawPlayer(name, amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        if (amount < 0.0) failure(amount, "음수 금액은 줄 수 없습니다") else change(player, whole(amount), "vault:deposit")

    override fun depositPlayer(player: OfflinePlayer, world: String?, amount: Double): EconomyResponse = depositPlayer(player, amount)

    override fun depositPlayer(name: String, amount: Double): EconomyResponse =
        byName(name)?.let { depositPlayer(it, amount) } ?: failure(amount, "모르는 플레이어: $name")

    override fun depositPlayer(name: String, world: String?, amount: Double): EconomyResponse = depositPlayer(name, amount)

    private fun change(player: OfflinePlayer, delta: Long, reason: String): EconomyResponse {
        val def = def() ?: return failure(delta.toDouble(), "기본 화폐가 없습니다")
        val amount = kotlin.math.abs(delta).toDouble()
        if (delta == 0L) return EconomyResponse(0.0, getBalance(player), ResponseType.SUCCESS, null)
        val after = eco.accounts.add(player.uniqueId, def, delta, reason)
            ?: return EconomyResponse(0.0, getBalance(player), ResponseType.FAILURE, if (delta < 0) "잔고가 모자랍니다" else "최대 금액을 넘습니다")
        return EconomyResponse(amount, after.toDouble(), ResponseType.SUCCESS, null)
    }

    private fun failure(amount: Double, why: String) = EconomyResponse(amount, 0.0, ResponseType.FAILURE, why)

    // --- 은행(없음) -----------------------------------------------------------------------

    private fun noBank() = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "은행은 지원하지 않습니다")

    override fun createBank(name: String, player: String): EconomyResponse = noBank()

    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = noBank()

    override fun deleteBank(name: String): EconomyResponse = noBank()

    override fun bankBalance(name: String): EconomyResponse = noBank()

    override fun bankHas(name: String, amount: Double): EconomyResponse = noBank()

    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = noBank()

    override fun bankDeposit(name: String, amount: Double): EconomyResponse = noBank()

    override fun isBankOwner(name: String, playerName: String): EconomyResponse = noBank()

    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = noBank()

    override fun isBankMember(name: String, playerName: String): EconomyResponse = noBank()

    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = noBank()

    override fun getBanks(): List<String> = emptyList()
}
