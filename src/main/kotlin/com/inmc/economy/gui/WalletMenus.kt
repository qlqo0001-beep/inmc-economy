package com.inmc.economy.gui

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 내 지갑 — 화폐마다 잔고와 할 수 있는 일.
 *
 * 좌클릭은 은행형 실물 화폐의 입금(가방의 것 전부), 우클릭은 출금, Shift+좌클릭은 보내기, Shift+우클릭은 수표.
 * 할 수 없는 것은 로어에 안 적는다 — 적어 두고 막으면 "왜 안 되지"를 묻게 된다.
 */
class WalletMenu(eco: Eco, private val viewer: Player) : Menu(eco, SIZE, Text.renderFlat("<dark_gray>내 지갑</dark_gray>")) {

    override fun draw() {
        clear()
        val shown = eco.currencies.all()
        for ((slot, def) in Paging.slice(shown, 0).withIndex()) {
            set(slot, tile(def, lore(def))) { event ->
                when {
                    event.isShiftClick && event.isRightClick -> cheque(def)
                    event.isShiftClick -> pay(def)
                    event.isRightClick && def.bank -> withdrawItems(def)
                    event.isLeftClick && def.bank -> depositItems(def)
                }
            }
        }
        set(SLOT_RANK, Icon.of(Material.GOLD_BLOCK, "<gold>순위</gold>", listOf("<gray>화폐마다 가장 많이 가진 사람.</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
            val first = eco.currencies.all().firstOrNull { it.ranked && !it.inventory }
            if (first != null) RankMenu(eco, viewer, first.id).open(viewer)
        }
        if (viewer.hasPermission(ADMIN)) {
            set(SLOT_ADMIN, Icon.of(Material.COMMAND_BLOCK, "<red>화폐 관리</red>", listOf("<gray>화폐 만들기·고치기, 플레이어 잔고·거래 기록.</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
                CurrencyListMenu(eco, viewer).open(viewer)
            }
        }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun balance(def: CurrencyDef): Long =
        if (def.inventory) eco.items.count(viewer, def) else eco.accounts.balance(viewer.uniqueId, def)

    private fun lore(def: CurrencyDef): List<String> = buildList {
        add("<gray>잔고: <white>" + def.format(balance(def)) + "</white></gray>")
        add("<dark_gray>" + def.kind.display + (if (def.kind == com.inmc.economy.currency.Kind.ITEM) " · " + def.mode.display else "") + " · " + def.scope.display + "</dark_gray>")
        if (def.inventory) add("<dark_gray>가방에 든 개수가 곧 잔고입니다.</dark_gray>")
        add("")
        if (def.bank) {
            add("<yellow>▶ 좌클릭: 가방의 것 전부 입금</yellow>")
            add("<yellow>▶ 우클릭: 출금</yellow>")
        }
        if (def.transferable && !def.inventory && viewer.hasPermission(PAY)) add("<yellow>▶ Shift+좌클릭: 보내기</yellow>")
        if (def.cheque && !def.inventory && viewer.hasPermission(CHEQUE)) add("<yellow>▶ Shift+우클릭: 수표 발행</yellow>")
    }

    private fun depositItems(def: CurrencyDef) {
        val moved = eco.items.depositItems(viewer, def, null)
        eco.messages.send(viewer, if (moved > 0) "bank-deposited" else "bank-nothing", ph(def).amount(def.format(moved)).balance(def.format(balance(def))))
        refresh()
    }

    private fun withdrawItems(def: CurrencyDef) {
        promptAmount(viewer, def.name + " 출금", 1, reopen = { open(viewer) }) { amount ->
            if (!eco.items.withdrawItems(viewer, def, amount)) eco.messages.send(viewer, "not-enough", ph(def).amount(def.format(amount)))
            else eco.messages.send(viewer, "bank-withdrew", ph(def).amount(def.format(amount)).balance(def.format(balance(def))))
        }
    }

    private fun pay(def: CurrencyDef) {
        if (!def.transferable || def.inventory || !viewer.hasPermission(PAY)) return
        var target: java.util.UUID? = null
        Editors.promptText(eco.prompts, viewer, "받을 사람", listOf("<gray>이름을 적으세요.</gray>"), reopen = {
            val to = target
            if (to == null) open(viewer) else promptAmount(viewer, def.name + " 보내기", 1, reopen = { open(viewer) }) { amount -> eco.transfers.pay(viewer, to, def, amount) }
        }) { raw ->
            target = eco.findPlayer(raw.trim())
            if (target == null) eco.messages.send(viewer, "player-not-found", ph(def).player(raw.trim()))
        }
    }

    private fun cheque(def: CurrencyDef) {
        if (!def.cheque || def.inventory || !viewer.hasPermission(CHEQUE)) return
        promptAmount(viewer, def.name + " 수표", 1, reopen = { open(viewer) }) { amount -> eco.cheques.issue(viewer, def, amount) }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_RANK = 48
        const val SLOT_ADMIN = 50
        const val ADMIN = "inmceconomy.admin"
        const val PAY = "inmceconomy.pay"
        const val CHEQUE = "inmceconomy.cheque"
    }
}

/** 한 화폐의 순위. 가방 속 개수형은 접속하지 않은 사람의 잔고를 몰라 순위가 없다. */
class RankMenu(eco: Eco, private val viewer: Player, private var currency: String) :
    Menu(eco, SIZE, Text.renderFlat("<dark_gray>순위</dark_gray>")) {

    override fun draw() {
        clear()
        // 연 사람에게는 지금 값을 — 몇 초마다 다시 세는 것은 PlaceholderAPI 처럼 자주 묻는 쪽을 위한 것이다.
        eco.ranks.refresh()
        val ranked = eco.currencies.all().filter { it.ranked && !it.inventory }
        val def = eco.currencies.get(currency)?.takeIf { it in ranked } ?: ranked.firstOrNull() ?: return viewer.closeInventory()
        currency = def.id
        for ((index, entry) in eco.ranks.top(def.id).take(Paging.PER_PAGE).withIndex()) {
            val medal = when (index) { 0 -> Material.GOLD_BLOCK; 1 -> Material.IRON_BLOCK; 2 -> Material.COPPER_BLOCK; else -> Material.PAPER }
            set(index, Icon.of(medal, "<yellow>" + (index + 1) + "위</yellow> <white>" + eco.nameOf(entry.player) + "</white>", listOf("<gray>" + def.format(entry.amount) + "</gray>")))
        }
        val mine = eco.ranks.position(def.id, viewer.uniqueId)
        set(SLOT_MINE, Icon.of(Material.PLAYER_HEAD, "<aqua>내 순위: <white>" + (mine?.let { it.toString() + "위" } ?: "순위 밖") + "</white></aqua>",
            listOf("<gray>" + def.format(eco.accounts.balance(viewer.uniqueId, def)) + "</gray>", "<dark_gray>" + eco.config.rankRefreshSeconds + "초마다 다시 셉니다.</dark_gray>")))
        set(SLOT_CURRENCY, Icon.annotate(iconOf(def), def.name, Editors.optionList(ranked, def) { it.name } + Editors.cycleHint)) { event ->
            currency = Editors.cycle(event, ranked, def).id
            refresh()
        }
        set(Paging.SLOT_BACK, Icon.back()) { WalletMenu(eco, viewer).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_MINE = 48
        const val SLOT_CURRENCY = 49
    }
}
