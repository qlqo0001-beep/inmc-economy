package com.inmc.economy.listener

import com.inmc.economy.Eco
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot

class EconomyListener(private val eco: Eco) : Listener {

    /**
     * 통합 화폐는 다른 서버에서 방금 바뀌었을 수 있다 — 들어오기 직전에 이 사람의 줄을 새로 읽는다.
     * 비동기 사건이라 기다려도 된다. 공용 DB 가 없으면(서버 하나) 할 일이 없다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val network = eco.db.network ?: return
        val ids = eco.currencies.all().filter { it.scope == com.inmc.economy.currency.Scope.NETWORK }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        runCatching { eco.db.call { eco.db.balances(network, event.uniqueId) } }
            .onSuccess { rows -> eco.accounts.external(rows.filter { it.currency in ids }) }
            .onFailure { eco.logger.warning("통합 화폐를 읽지 못했습니다(${event.name}): ${it.message}") }
    }

    /** 접속하지 않은 동안 받은 실물 화폐. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        eco.items.deliverPending(event.player)
    }

    /** 화면이 금액·이름을 물어본 뒤의 채팅. 이게 없으면 영영 기다린다. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!eco.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (eco.prompts.submit(player, text)) event.isCancelled = true
    }

    /** 수표 우클릭. 허공 클릭은 처음부터 "취소됨"이라 ignoreCancelled 를 쓰지 않는다(워크스페이스 지뢰 14). */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.useItemInHand() == Event.Result.DENY) return
        val stack = event.item ?: return
        eco.cheques.idOf(stack) ?: return
        event.isCancelled = true
        if (!event.player.hasPermission(CHEQUE)) return eco.messages.send(event.player, "no-permission")
        eco.cheques.redeem(event.player, stack)
    }

    private companion object {
        const val CHEQUE = "inmceconomy.cheque"
    }
}
