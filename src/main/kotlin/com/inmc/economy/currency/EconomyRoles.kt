package com.inmc.economy.currency

import com.inmc.economy.Eco
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import org.bukkit.Material

/**
 * 화폐가 커스텀아이템에 내놓는 역할(core [ItemRoles]) — **실물 화폐**(값: 어느 화폐인지)와 **수표 모양**.
 *
 * 커스텀아이템이 있으면 실물 화폐의 아이템은 그 역할을 맡은 커스텀아이템이다([sync]). 화폐 설정 화면에서 손에 든 것으로 정해도
 * 커스텀아이템의 역할에서 정해도 같다. **바닐라 그대로인 화폐(에메랄드)는 옮기지 않는다** — 커스텀아이템으로 바꾸면 주민 거래에 쓰는
 * 평범한 에메랄드와 갈라진다. 옮긴 화폐도 이미 나가 있는 옛 아이템은 계속 센다(legacy).
 */
object EconomyRoles {

    const val OWNER = "화폐"

    val CURRENCY = "economy.currency"
    val CHEQUE = "economy.cheque"

    fun roles(eco: Eco): List<ItemRoles.Role> = listOf(
        ItemRoles.Role(
            CURRENCY, OWNER, "실물 화폐", Material.GOLD_NUGGET,
            listOf("이 아이템이 그 화폐의 실물이 됩니다(가방 속 개수형·은행형)."),
            listOf(ItemRoles.Choice("currency", "화폐", { eco.currencies.all().filter { it.kind == Kind.ITEM }.map { it.id to it.name } })),
        ),
        ItemRoles.Role(
            CHEQUE, OWNER, "수표 모양", Material.PAPER,
            listOf("수표가 이 아이템의 재질·모델로 만들어집니다.", "금액·발행인은 수표마다 따로 적힙니다."),
        ),
    )

    private fun ours(ref: ItemRef?) = (ref as? ItemRef.Namespaced)?.namespace.equals("inmc", ignoreCase = true)

    /**
     * 화폐 설정 화면에서 손에 든 것을 실물로 — 바닐라가 아니면 커스텀아이템으로 만들어(이미 그렇다면 그대로) 역할을 붙이고 이 화폐의
     * 역할을 맡던 다른 아이템에서는 뗀다. 바닐라면 역할을 다 떼고 false(부르는 쪽이 화폐에 그대로 적는다).
     */
    fun set(eco: Eco, def: CurrencyDef, hand: org.bukkit.inventory.ItemStack): Boolean {
        if (!ItemRoles.active) return false
        for (holder in ItemRoles.holders(CURRENCY)) if (holder.values["currency"] == def.id) ItemRoles.assign(holder.ref, CURRENCY, null)
        val captured = eco.resolver.capture(hand)
        if (captured.ref is ItemRef.Vanilla) return false
        val ref = ItemRoles.adopt(hand, def.id) ?: return false
        ItemRoles.assign(ref, CURRENCY, ItemRoles.withLegacy(mapOf("currency" to def.id), captured))
        return true
    }

    private var syncing = false

    /** 실물 화폐를 역할에 맞춘다(처음이면 바닐라가 아닌 옛 아이템을 옮긴다). 수표 모양도 다시 읽는다. */
    fun sync(eco: Eco) {
        if (!ItemRoles.active) {
            eco.items.legacy = emptyMap()
            eco.cheques.appearance = null
            return
        }
        if (syncing) return
        syncing = true
        try {
            for (def in eco.currencies.all()) {
                val item = def.item ?: continue
                if (def.kind != Kind.ITEM || ours(item.ref) || item.ref is ItemRef.Vanilla) continue
                val stack = eco.resolver.create(item, 1)?.let { ItemRoles.sample(it, item) } ?: continue
                val ref = ItemRoles.adopt(stack, def.id) ?: continue
                ItemRoles.assign(ref, CURRENCY, ItemRoles.withLegacy(mapOf("currency" to def.id), item))
                eco.plugin.logger.info("'${def.id}' 화폐의 실물을 커스텀아이템 '${ref.id}' 로 옮겼습니다")
            }
            val legacy = HashMap<String, kr.inmc.core.item.StoredItem>()
            for (holder in ItemRoles.holders(CURRENCY)) {
                val def = eco.currencies.get(holder.values["currency"]) ?: continue
                if (def.item?.ref != holder.ref) eco.currencies.put(def.copy(item = holder.item()))
                holder.legacy()?.let { legacy[def.id] = it }
            }
            eco.items.legacy = legacy
            eco.cheques.appearance = ItemRoles.holders(CHEQUE).firstOrNull()
        } finally {
            syncing = false
        }
    }
}
