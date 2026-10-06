package com.inmc.economy.currency

import com.inmc.economy.Eco
import com.inmc.economy.store.Accounts
import com.inmc.economy.util.Ph
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** 명령어와 화면이 같이 쓰는 거래. 메시지까지 여기서 보낸다 — 두 길이 다르게 말하지 않게. */
class Transfers(private val eco: Eco) {

    enum class AdminAction(val label: String) { GIVE("지급"), TAKE("차감"), SET("설정") }

    /** 송금. 빼기와 더하기를 **한꺼번에** 한다 — 하나만 되면 돈이 사라지거나 생긴다. */
    fun pay(from: Player, to: UUID, def: CurrencyDef, amount: Long): Boolean {
        val ph = Ph.of().currency(def.name).amount(def.format(amount)).player(eco.nameOf(to))
        when {
            amount <= 0 -> return false.also { eco.messages.send(from, "invalid-amount") }
            !def.transferable || def.inventory -> return false.also { eco.messages.send(from, "pay-not-allowed", ph) }
            to == from.uniqueId -> return false.also { eco.messages.send(from, "pay-self") }
        }
        // 수수료는 보내는 사람이 더 낸다. 받는 사람은 온전히 받는다.
        val fee = eco.config.feeFor(amount)
        val total = amount + fee
        val fromName = from.name
        val toName = eco.nameOf(to)
        val done = eco.accounts.transact(listOf(
            Accounts.Op(from.uniqueId, def, -total, "pay:to:$toName"),
            Accounts.Op(to, def, amount, "pay:from:$fromName"),
        ))
        if (done == null) {
            val short = eco.accounts.balance(from.uniqueId, def) < total
            val need = Ph.of().currency(def.name).amount(def.format(total)).player(eco.nameOf(to))
            eco.messages.send(from, if (short) "not-enough" else "pay-over-max", need)
            return false
        }
        eco.messages.send(from, "paid", ph.copy().balance(def.format(done[0])))
        if (fee > 0) eco.messages.send(from, "fee-charged", Ph.of().currency(def.name).amount(def.format(fee)))
        Bukkit.getPlayer(to)?.let { eco.messages.send(it, "received", Ph.of().currency(def.name).amount(def.format(amount)).player(fromName).balance(def.format(done[1]))) }
        return true
    }

    /** 관리자 지급·차감·설정. 가방 속 개수형은 지급·차감만(접속 중일 때). */
    fun admin(sender: CommandSender, target: UUID, def: CurrencyDef, action: AdminAction, amount: Long): Boolean {
        val name = eco.nameOf(target)
        val ph = Ph.of().currency(def.name).amount(def.format(amount)).player(name).value(action.label)
        if (amount < 0 || (amount == 0L && action != AdminAction.SET)) return false.also { eco.messages.send(sender, "invalid-amount") }
        val reason = "admin:" + action.name.lowercase() + ":" + sender.name
        val ok = if (def.inventory) {
            val player = Bukkit.getOfflinePlayer(target)
            when (action) {
                AdminAction.GIVE -> eco.items.give(player, def, amount, reason)
                AdminAction.TAKE -> eco.items.take(player, def, amount, reason)
                AdminAction.SET -> return false.also { eco.messages.send(sender, "inventory-no-set", ph) }
            }
        } else {
            when (action) {
                AdminAction.GIVE -> eco.accounts.add(target, def, amount, reason)
                AdminAction.TAKE -> eco.accounts.add(target, def, -amount, reason)
                AdminAction.SET -> eco.accounts.set(target, def, amount, reason)
            } != null
        }
        if (!ok) return false.also { eco.messages.send(sender, "admin-failed", ph) }
        val balance = if (def.inventory) eco.items.count(Bukkit.getOfflinePlayer(target), def) else eco.accounts.balance(target, def)
        eco.messages.send(sender, "admin-done", ph.copy().balance(def.format(balance)))
        return true
    }
}
