package com.inmc.economy.currency

import com.inmc.economy.Eco
import com.inmc.economy.store.Change
import com.inmc.economy.util.Ph
import kr.inmc.core.item.StoredItem
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 실물 화폐.
 *
 * - **가방 속 개수형**([ItemMode.INVENTORY]): 가방(단축바 포함, 방어구·왼손 제외)에 든 그 아이템의 개수가 곧 잔고다.
 *   장부가 없어 거래 기록만 남긴다. 가방은 그 사람을 가진 스레드에서만 만진다 — 다른 스레드에서 오면 거절한다.
 *   접속하지 않은 사람에게 주는 것은 다음 접속 때 준다([deliverPending]).
 * - **은행형**([ItemMode.BANK]): 잔고는 장부의 숫자([com.inmc.economy.store.Accounts])고, 아이템으로 넣고 뺀다.
 */
class ItemCurrencies(private val eco: Eco) {

    /** 다른 스레드(PlaceholderAPI)가 물을 때 줄 마지막으로 센 값. */
    private val lastCount = ConcurrentHashMap<String, Long>()

    private fun owned(player: Player): Boolean = Bukkit.isOwnedByCurrentRegion(player)

    /** 화폐 id → 커스텀아이템으로 옮기기 전의 아이템. 이미 나가 있는 옛 실물도 센다([EconomyRoles]). */
    @Volatile
    var legacy: Map<String, StoredItem> = emptyMap()

    private fun matches(stack: org.bukkit.inventory.ItemStack?, spec: StoredItem, old: StoredItem?): Boolean =
        eco.matcher.matches(stack, spec) || (old != null && eco.matcher.matches(stack, old))

    /** 가방 속 개수 — 배낭(core CarriedStorage, 2026-09-30)까지. 접속하지 않았으면 0, 다른 스레드면 마지막으로 센 값. */
    fun count(player: OfflinePlayer, def: CurrencyDef): Long {
        val online = player.player ?: return 0
        val key = online.uniqueId.toString() + "|" + def.id
        if (!owned(online)) return lastCount[key] ?: 0
        val spec = def.item ?: return 0
        val old = legacy[def.id]
        val counted = online.inventory.storageContents.sumOf { if (matches(it, spec, old)) it!!.amount.toLong() else 0L } +
            kr.inmc.core.integration.CarriedStorage.count(online) { matches(it, spec, old) }
        lastCount[key] = counted
        return counted
    }

    /** 가방에서 뺀다. 모자라거나 뺄 수 없으면(접속 안 함·다른 스레드) 아무것도 안 하고 false. */
    fun take(player: OfflinePlayer, def: CurrencyDef, amount: Long, reason: String): Boolean {
        if (amount <= 0) return true
        val online = player.player ?: return false
        if (!owned(online)) return false
        val spec = def.item ?: return false
        val have = count(online, def)
        if (have < amount) return false
        remove(online, spec, amount, legacy[def.id])
        ledger(online.uniqueId, def, -amount, have - amount, reason)
        return true
    }

    /** 가방에 넣는다. 가득 차면 발밑에. 접속하지 않았으면 다음 접속 때. */
    fun give(player: OfflinePlayer, def: CurrencyDef, amount: Long, reason: String): Boolean {
        if (amount <= 0) return true
        val spec = def.item ?: return false
        val online = player.player
        when {
            online != null && owned(online) -> {
                hand(online, spec, amount)
                ledger(online.uniqueId, def, amount, count(online, def), reason)
            }
            online != null -> {
                online.scheduler.run(eco.plugin, { hand(online, spec, amount) }, null)
                ledger(online.uniqueId, def, amount, 0, reason)
            }
            else -> {
                val id = player.uniqueId
                eco.db.run("받을 실물 화폐") { eco.db.addPending(eco.db.local, id, def.id, amount) }
                ledger(id, def, amount, 0, "$reason (다음 접속 때)")
            }
        }
        return true
    }

    /** 은행형 입금 — 가방의 그 아이템을 잔고로. [amount] 가 null 이면 전부. @return 넣은 개수. */
    fun depositItems(player: Player, def: CurrencyDef, amount: Long?): Long {
        val spec = def.item ?: return 0
        val have = count(player, def)
        var moving = if (amount == null) have else minOf(amount, have)
        if (def.max > 0) moving = minOf(moving, def.max - eco.accounts.balance(player.uniqueId, def)).coerceAtLeast(0)
        if (moving <= 0) return 0
        remove(player, spec, moving, legacy[def.id])
        if (eco.accounts.add(player.uniqueId, def, moving, "bank:deposit") == null) {
            // 한도에 걸렸다 — 뺀 것을 돌려준다.
            hand(player, spec, moving)
            return 0
        }
        return moving
    }

    /** 은행형 출금 — 잔고를 그 아이템으로. 잔고가 모자라면 false. */
    fun withdrawItems(player: Player, def: CurrencyDef, amount: Long): Boolean {
        val spec = def.item ?: return false
        if (amount <= 0) return false
        if (eco.accounts.add(player.uniqueId, def, -amount, "bank:withdraw") == null) return false
        hand(player, spec, amount)
        return true
    }

    /** 접속하지 않은 동안 받은 실물 화폐를 준다. 접속할 때. */
    fun deliverPending(player: Player) {
        val id = player.uniqueId
        eco.db.run("받을 실물 화폐 꺼내기") {
            val rows = eco.db.takePending(eco.db.local, id)
            if (rows.isEmpty()) return@run
            player.scheduler.run(eco.plugin, {
                for (row in rows) {
                    val def = eco.currencies.get(row.currency)
                    val spec = def?.item
                    if (def == null || spec == null) {
                        // 화폐가 지워졌다 — 버리지 않고 다시 적어 둔다.
                        eco.db.run("받을 실물 화폐 되돌리기") { eco.db.addPending(eco.db.local, id, row.currency, row.amount) }
                        continue
                    }
                    hand(player, spec, row.amount)
                    eco.messages.send(player, "pending-delivered", Ph.of().currency(def.name).amount(def.format(row.amount)))
                }
            }, {
                // 그 사이 나갔다 — 다시 적어 둔다.
                eco.db.run("받을 실물 화폐 되돌리기") { for (row in rows) eco.db.addPending(eco.db.local, id, row.currency, row.amount) }
            })
        }
    }

    /** 아이템을 만들어 가방에. 한 번에 최대 겹침만큼씩, 넘치면 발밑에. */
    fun hand(player: Player, spec: StoredItem, amount: Long) {
        val sample = eco.resolver.create(spec, 1) ?: return
        val stack = sample.maxStackSize.coerceAtLeast(1)
        var left = amount
        while (left > 0) {
            val size = minOf(left, stack.toLong()).toInt()
            val item = sample.clone().also { it.amount = size }
            for (overflow in player.inventory.addItem(item).values) player.world.dropItemNaturally(player.location, overflow)
            left -= size
        }
    }

    private fun remove(player: Player, spec: StoredItem, amount: Long, old: StoredItem? = null) {
        var left = amount
        val inventory = player.inventory
        for (slot in 0 until inventory.storageContents.size) {
            if (left <= 0) break
            val stack = inventory.getItem(slot) ?: continue
            if (!matches(stack, spec, old)) continue
            val taken = minOf(left, stack.amount.toLong()).toInt()
            left -= taken
            // 돌려받은 것이 복제본일 수 있어 줄인 것을 다시 넣는다.
            if (taken >= stack.amount) inventory.setItem(slot, null) else inventory.setItem(slot, stack.also { it.amount -= taken })
        }
        // 가방에서 모자라면 배낭에서(가방 먼저).
        if (left > 0) kr.inmc.core.integration.CarriedStorage.take(player, left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) { matches(it, spec, old) }
    }

    /** 가방 속 개수형은 장부가 없어 기록만. */
    private fun ledger(player: UUID, def: CurrencyDef, delta: Long, balance: Long, reason: String) {
        val change = Change(player, def.id, delta, balance, reason, ledgerOnly = true)
        eco.db.run("실물 화폐 기록") { eco.db.apply(eco.db.local, listOf(change)) }
    }
}
