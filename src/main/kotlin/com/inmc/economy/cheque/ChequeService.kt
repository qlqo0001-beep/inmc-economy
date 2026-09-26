package com.inmc.economy.cheque

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.Scope
import com.inmc.economy.store.Cheque
import com.inmc.economy.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 수표 — 잔고를 아이템으로 꺼내 건네고, 우클릭으로 다시 잔고에 넣는다.
 *
 * **수표마다 id 가 있고 DB 에 한 줄이 있다.** 쓸 때 "아직 안 쓴 것"일 때만 쓴 것으로 적으므로([com.inmc.economy.store.Db.redeemCheque])
 * 복사 버그로 늘어난 수표는 한 장만 돈이 된다. 아이템에 적힌 금액이 아니라 DB 의 금액을 준다 — 아이템을 고쳐도 소용없다.
 *
 * 권한 `inmceconomy.cheque` 는 기본이 관리자뿐이다(유저에게는 필요할 때 연다).
 */
class ChequeService(private val eco: Eco) {

    /** 지금 쓰는 중인 수표 — DB 답을 기다리는 동안 두 번 누르는 것을 막는다. */
    private val redeeming = ConcurrentHashMap.newKeySet<UUID>()

    /** 발행. 잔고에서 빼고, DB 에 적은 뒤, 아이템을 준다. DB 가 실패하면 되돌린다. */
    fun issue(player: Player, def: CurrencyDef, amount: Long): Boolean {
        if (!def.cheque || def.inventory) {
            eco.messages.send(player, "cheque-not-allowed", Ph.of().currency(def.name))
            return false
        }
        if (amount <= 0) return false
        val id = UUID.randomUUID()
        if (eco.accounts.add(player.uniqueId, def, -amount, "cheque:issue:$id") == null) {
            eco.messages.send(player, "not-enough", Ph.of().currency(def.name).amount(def.format(amount)))
            return false
        }
        val cheque = Cheque(id, def.id, amount, player.uniqueId, System.currentTimeMillis())
        eco.db.run("수표 발행") {
            val ok = runCatching { eco.db.issueCheque(eco.db.source(def.scope == Scope.NETWORK), cheque) }
                .onFailure { eco.logger.severe("수표 발행 기록 실패 - 금액을 돌려줍니다: ${it.message}") }
                .isSuccess
            player.scheduler.run(eco.plugin, {
                if (ok) {
                    for (left in player.inventory.addItem(item(def, cheque)).values) player.world.dropItemNaturally(player.location, left)
                    eco.messages.send(player, "cheque-issued", Ph.of().currency(def.name).amount(def.format(amount)))
                } else {
                    eco.accounts.add(player.uniqueId, def, amount, "cheque:refund:$id")
                    eco.messages.send(player, "cheque-failed")
                }
            }, {
                // 그 사이 나갔다. 기록이 됐으면 돈을 돌려준다 — 아이템을 줄 수 없으니 수표는 쓸 수 없는 채로 남는다.
                eco.accounts.add(player.uniqueId, def, amount, "cheque:refund:$id")
            })
        }
        return true
    }

    /** 이 아이템이 수표면 그 id. */
    fun idOf(stack: ItemStack?): UUID? {
        val meta = stack?.itemMeta ?: return null
        val raw = meta.persistentDataContainer.get(ID, PersistentDataType.STRING) ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    /** 우클릭. DB 가 "이번에 쓴 것"이라고 답하면 잔고에 넣고 아이템을 없앤다. */
    fun redeem(player: Player, stack: ItemStack) {
        val id = idOf(stack) ?: return
        val currency = stack.itemMeta.persistentDataContainer.get(CURRENCY, PersistentDataType.STRING)
        val def = eco.currencies.get(currency) ?: return eco.messages.send(player, "cheque-unknown-currency", Ph.of().currency(currency.orEmpty()))
        if (!redeeming.add(id)) return
        val source = eco.db.source(def.scope == Scope.NETWORK)
        val by = player.uniqueId
        eco.db.run("수표 쓰기") {
            val cheque = try {
                eco.db.redeemCheque(source, id, by, System.currentTimeMillis())
            } catch (t: Throwable) {
                redeeming.remove(id)
                throw t
            }
            player.scheduler.run(eco.plugin, {
                redeeming.remove(id)
                if (cheque == null) {
                    removeCheque(player, id)
                    eco.messages.send(player, "cheque-used")
                    return@run
                }
                val target = eco.currencies.get(cheque.currency) ?: def
                if (eco.accounts.add(by, target, cheque.amount, "cheque:redeem:$id") == null) {
                    // 한도에 걸렸다 — 쓴 것을 되돌려 아이템을 살린다.
                    eco.db.run("수표 되돌리기") { eco.db.unredeemCheque(source, id) }
                    eco.messages.send(player, "over-max", Ph.of().currency(target.name))
                    return@run
                }
                removeCheque(player, id)
                eco.messages.send(player, "cheque-redeemed", Ph.of().currency(target.name).amount(target.format(cheque.amount)))
            }, { redeeming.remove(id) })
        }
    }

    private fun removeCheque(player: Player, id: UUID) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val stack = inventory.getItem(slot) ?: continue
            if (idOf(stack) != id) continue
            inventory.setItem(slot, if (stack.amount > 1) stack.also { it.amount -= 1 } else null)
            return
        }
    }

    /** 커스텀아이템이 정한 수표 모양(재질·모델). [com.inmc.economy.currency.EconomyRoles] 참조. */
    @Volatile
    var appearance: kr.inmc.core.integration.ItemRoles.Holder? = null

    fun item(def: CurrencyDef, cheque: Cheque): ItemStack {
        val config = eco.config
        val look = appearance
        return ItemStack(look?.material ?: config.chequeMaterial).apply {
            editMeta { meta ->
                meta.displayName(Text.renderFlat("<gold>수표</gold> <white>" + def.format(cheque.amount) + "</white>"))
                meta.lore(
                    listOf(
                        "<gray>화폐: " + def.name + "</gray>",
                        "<gray>발행: <white>" + eco.nameOf(cheque.issuer) + "</white> · " + DATE.format(Date(cheque.issuedAt)) + "</gray>",
                        "",
                        "<yellow>우클릭하면 잔고에 들어갑니다.</yellow>",
                        "<dark_gray>" + cheque.id.toString().take(8) + "</dark_gray>",
                    ).map(Text::renderFlat),
                )
                val model = look?.modelData?.takeIf { it > 0 } ?: config.chequeModel
                if (model > 0) meta.setCustomModelData(model)
                look?.itemModel?.takeIf { it.isNotBlank() }?.let(org.bukkit.NamespacedKey::fromString)?.let { runCatching { meta.setItemModel(it) } }
                meta.persistentDataContainer.set(ID, PersistentDataType.STRING, cheque.id.toString())
                meta.persistentDataContainer.set(CURRENCY, PersistentDataType.STRING, def.id)
                meta.persistentDataContainer.set(AMOUNT, PersistentDataType.LONG, cheque.amount)
            }
        }
    }

    private companion object {
        /** 네임스페이스는 고정 — 플러그인 이름이 바뀌어도 돌아다니는 수표가 정체를 잃지 않게. */
        const val NAMESPACE = "inmceco"
        val ID = NamespacedKey(NAMESPACE, "cheque")
        val CURRENCY = NamespacedKey(NAMESPACE, "cheque_currency")
        val AMOUNT = NamespacedKey(NAMESPACE, "cheque_amount")
        val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm")
    }
}
