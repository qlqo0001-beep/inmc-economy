package com.inmc.economy.gui

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.ItemMode
import com.inmc.economy.currency.Kind
import com.inmc.economy.currency.Scope
import com.inmc.economy.currency.Transfers
import com.inmc.economy.store.LedgerRow
import com.inmc.economy.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/** 화폐 목록(관리). 좌클릭 설정, 아래 버튼으로 새 화폐·플레이어 잔고. */
class CurrencyListMenu(eco: Eco, private val viewer: Player) : Menu(eco, SIZE, Text.renderFlat("<dark_gray>화폐 관리</dark_gray>")) {

    override fun draw() {
        clear()
        val all = eco.currencies.all()
        for ((slot, def) in Paging.slice(all, 0).withIndex()) {
            set(slot, tile(def, buildList {
                add("<dark_gray>id " + def.id + "</dark_gray>")
                add("<gray>" + def.kind.display + (if (def.kind == Kind.ITEM) " · " + def.mode.display else "") + " · " + def.scope.display + "</gray>")
                if (def == eco.currencies.default()) add("<gold>★ 기본 화폐 (Vault)</gold>")
                add("")
                add("<yellow>▶ 클릭: 설정</yellow>")
            })) { CurrencyEditMenu(eco, viewer, def.id).open(viewer) }
        }
        set(SLOT_CREATE, Icon.of(Material.WRITABLE_BOOK, "<green>새 화폐</green>", listOf(
            "<gray>id 는 영문 소문자·숫자·밑줄 — 명령어에 씁니다.</gray>", "<gray>보이는 이름은 한글로 따로 정합니다.</gray>", "", "<yellow>▶ 클릭: id 입력</yellow>",
        ))) { create() }
        set(SLOT_PLAYER, Icon.of(Material.PLAYER_HEAD, "<aqua>플레이어 잔고</aqua>", listOf(
            "<gray>잔고 보기·지급·차감·설정, 거래 기록.</gray>", "", "<yellow>▶ 클릭: 이름 입력</yellow>",
        ))) { findPlayer() }
        set(Paging.SLOT_BACK, Icon.back()) { WalletMenu(eco, viewer).open(viewer) }
        set(SLOT_HUB, Icon.of(Material.COMPASS, "<gold>어드민 메뉴로</gold>", "<gray>각 플러그인 설정 허브로 돌아갑니다.</gray>")) {
            viewer.performCommand("메뉴 어드민")
        }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun create() {
        var created: String? = null
        Editors.promptText(eco.prompts, viewer, "새 화폐 id", listOf("<gray>예: <white>point</white>, <white>event_coin</white></gray>"), reopen = {
            created?.let { CurrencyEditMenu(eco, viewer, it).open(viewer) } ?: open(viewer)
        }) { raw ->
            val id = raw.trim().lowercase()
            when {
                !CurrencyDef.ID.matches(id) -> eco.messages.send(viewer, "invalid-id", Ph.of().value(raw))
                eco.currencies.get(id) != null -> eco.messages.send(viewer, "already-exists", Ph.of().value(id))
                else -> {
                    eco.currencies.put(CurrencyDef(id))
                    created = id
                }
            }
        }
    }

    private fun findPlayer() {
        var found: UUID? = null
        Editors.promptText(eco.prompts, viewer, "플레이어 이름", emptyList(), reopen = {
            found?.let { AccountMenu(eco, viewer, it).open(viewer) } ?: open(viewer)
        }) { raw ->
            found = eco.findPlayer(raw.trim())
            if (found == null) eco.messages.send(viewer, "player-not-found", Ph.of().player(raw.trim()))
        }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_CREATE = 48
        const val SLOT_PLAYER = 50
        const val SLOT_HUB = 52
    }
}

/**
 * 화폐 하나의 설정. **정의가 아니라 id 를 들고 있다** — 정의는 불변이라 한 칸을 고치면 새 객체가 된다.
 * 실물이 아니면 실물 칸(방식·아이템)은 안 보인다 — 보이면 "왜 안 먹지"를 묻게 된다.
 */
class CurrencyEditMenu(eco: Eco, private val viewer: Player, private val id: String) :
    Menu(eco, SIZE, Text.renderFlat("<dark_gray>화폐 — $id</dark_gray>")) {

    private fun def(): CurrencyDef? = eco.currencies.get(id)

    private fun change(edit: (CurrencyDef) -> CurrencyDef) {
        def()?.let { eco.currencies.put(edit(it)) }
        refresh()
    }

    override fun draw() {
        clear()
        val def = def() ?: return CurrencyListMenu(eco, viewer).open(viewer)
        fillEmpty(Icon.EDGE)
        set(SLOT_PREVIEW, tile(def, listOf("<gray>" + def.format(1234567) + "</gray>")))

        set(SLOT_NAME, Icon.of(Material.NAME_TAG, "<yellow>보이는 이름</yellow>", listOf("<white>" + def.name + "</white>", "<dark_gray>MiniMessage 서식</dark_gray>", "", "<yellow>▶ 클릭: 입력</yellow>"))) {
            Editors.promptText(eco.prompts, viewer, "보이는 이름", listOf("<gray>예: <white><gold>돈</gold></white></gray>"), reopen = { open(viewer) }) { raw ->
                if (raw.isNotBlank()) def()?.let { eco.currencies.put(it.copy(name = raw.trim())) }
            }
        }
        set(SLOT_UNIT, Icon.of(Material.OAK_SIGN, "<yellow>단위: <white>" + def.unit.ifEmpty { "없음" } + "</white></yellow>", listOf("<gray>금액 뒤에 붙는 글자 — " + def.format(1000) + "</gray>", "", "<yellow>▶ 클릭: 입력 (비우려면 '없음')</yellow>"))) {
            Editors.promptText(eco.prompts, viewer, "단위", listOf("<gray>예: <white>원</white>, <white>캐시</white>, <white>개</white></gray>"), reopen = { open(viewer) }) { raw ->
                val unit = raw.trim().let { if (it == "없음") "" else it }
                def()?.let { eco.currencies.put(it.copy(unit = unit)) }
            }
        }
        set(SLOT_ICON, Icon.relabel(plainIcon(def), "<yellow>아이콘: <white>" + (def.iconItem?.label() ?: def.icon.name) + "</white></yellow>", listOf(
            "<gray>화면에 보이는 모양(가상 화폐).</gray>", "<dark_gray>모델 번호·커스텀아이템 모양 그대로 됩니다.</dark_gray>", "",
            "<yellow>▶ 좌클릭: 손에 든 것으로 바꾸기</yellow>", "<yellow>▶ 우클릭: 기본 모양으로</yellow>",
        ))) { event ->
            if (event.isRightClick) return@set change { it.copy(icon = Material.GOLD_NUGGET, iconItem = null) }
            val hand = viewer.inventory.itemInMainHand
            if (hand.type.isAir) return@set eco.messages.send(viewer, "hand-empty")
            val item = if (eco.resolver.isPlainVanilla(hand)) null else eco.resolver.capture(hand)
            change { it.copy(icon = hand.type, iconItem = item) }
        }
        set(SLOT_KIND, Icon.of(if (def.kind == Kind.ITEM) Material.EMERALD else Material.PAPER, "<yellow>종류: <white>" + def.kind.display + "</white></yellow>",
            Editors.optionList(Kind.entries.toList(), def.kind) { it.display } + listOf("", "<gray>가상은 숫자만, 실물은 아이템.</gray>") + Editors.cycleHint)) { event ->
            change { it.copy(kind = Editors.cycle(event, Kind.entries.toList(), it.kind)).let(::keepDefaultValid) }
        }
        if (def.kind == Kind.ITEM) {
            set(SLOT_MODE, Icon.of(Material.CHEST, "<yellow>실물 방식: <white>" + def.mode.display + "</white></yellow>",
                Editors.optionList(ItemMode.entries.toList(), def.mode) { it.display } + listOf(
                    "", "<gray>가방 속 개수 — 가방에 든 개수가 곧 잔고. 내면 아이템이 빠집니다.</gray>",
                    "<gray>은행형 — 잔고는 숫자, /돈 입금·출금 으로 아이템과 바꿉니다.</gray>",
                ) + Editors.cycleHint)) { event ->
                change { it.copy(mode = Editors.cycle(event, ItemMode.entries.toList(), it.mode)).let(::keepDefaultValid) }
            }
            set(SLOT_ITEM, def.item?.let { eco.resolver.create(it, 1) }?.let { Icon.annotate(it, null, listOf("", "<yellow>▶ 클릭: 손에 든 것으로 바꾸기</yellow>", "<dark_gray>모델 번호·커스텀아이템 그대로 알아봅니다.</dark_gray>")) }
                ?: Icon.of(Material.BARRIER, "<red>아이템 없음</red>", listOf("<gray>화폐로 쓸 아이템을 손에 들고 누르세요.</gray>", "<dark_gray>에메랄드·모델 번호 붙은 아이템·커스텀아이템 전부 됩니다.</dark_gray>"))) {
                val hand = viewer.inventory.itemInMainHand
                if (hand.type.isAir) return@set eco.messages.send(viewer, "hand-empty")
                // 커스텀아이템이 있으면 그리로(바닐라 그대로인 것은 제외) — 커스텀아이템의 "실물 화폐" 역할에서도 보인다.
                if (com.inmc.economy.currency.EconomyRoles.set(eco, def, hand)) refresh() else change { it.copy(item = eco.resolver.capture(hand)) }
            }
        }
        set(SLOT_SCOPE, Icon.of(if (def.scope == Scope.NETWORK) Material.ENDER_EYE else Material.GRASS_BLOCK, "<yellow>범위: <white>" + def.scope.display + "</white></yellow>",
            Editors.optionList(Scope.entries.toList(), def.scope) { it.display } + listOf(
                "", "<gray>통합 — 여러 서버가 같은 잔고(config.yml 의 network DB).</gray>",
                if (eco.db.network == null) "<red>공용 DB 가 없어 지금은 이 서버에만 저장됩니다.</red>" else "<green>공용 DB 연결됨</green>",
                "<dark_gray>바꿔도 이미 쌓인 잔고는 옮겨지지 않습니다.</dark_gray>",
            ) + Editors.cycleHint)) { event ->
            change { it.copy(scope = Editors.cycle(event, Scope.entries.toList(), it.scope)) }
        }
        set(SLOT_DEFAULT, Icon.of(if (def == eco.currencies.default()) Material.NETHER_STAR else Material.GRAY_DYE,
            if (def == eco.currencies.default()) "<gold>★ 기본 화폐</gold>" else "<gray>기본 화폐로 정하기</gray>", listOf(
                "<gray>금액만 적힌 보상·가격(몬스터·랜덤박스·낚시…)과</gray>", "<gray>Vault 를 쓰는 다른 플러그인이 이 화폐를 씁니다.</gray>",
                if (def.canBeDefault) "" else "<red>가방 속 개수형은 기본이 될 수 없습니다.</red>",
            ))) {
            if (def.canBeDefault) change { it.copy(isDefault = true) }
        }
        set(SLOT_START, amountIcon(Material.SUNFLOWER, "처음 받는 금액", def.start, def)) {
            promptAmount(viewer, "처음 받는 금액", 0, reopen = { open(viewer) }) { v -> def()?.let { eco.currencies.put(it.copy(start = v)) } }
        }
        set(SLOT_MAX, amountIcon(Material.IRON_BARS, "최대 금액", def.max, def, "0 이면 없음")) {
            promptAmount(viewer, "최대 금액(0 이면 없음)", 0, reopen = { open(viewer) }) { v -> def()?.let { eco.currencies.put(it.copy(max = v)) } }
        }
        set(SLOT_TRANSFER, toggle(Material.ARROW, "보내기 허용", def.transferable, "/돈 보내기 로 다른 사람에게.")) { change { it.copy(transferable = !it.transferable) } }
        set(SLOT_RANKED, toggle(Material.GOLD_BLOCK, "순위에 보임", def.ranked, "/돈 순위 와 %inmceco_top_…%.")) { change { it.copy(ranked = !it.ranked) } }
        set(SLOT_CHEQUE, toggle(Material.PAPER, "수표 허용", def.cheque, "잔고를 수표 아이템으로 꺼냅니다. 권한 inmceconomy.cheque 도 필요합니다.")) { change { it.copy(cheque = !it.cheque) } }
        set(SLOT_DELETE, Icon.of(Material.LAVA_BUCKET, "<red>화폐 지우기</red>", listOf("<gray>정의만 지웁니다. 쌓인 잔고는 DB 에 남아</gray>", "<gray>같은 id 로 다시 만들면 돌아옵니다.</gray>"))) {
            ConfirmMenu(eco, "<red>'" + def.id + "' 화폐를 지울까요?</red>", listOf("<gray>잔고는 DB 에 남습니다.</gray>"), onConfirm = {
                eco.currencies.remove(def.id)
                CurrencyListMenu(eco, viewer).open(viewer)
            }, onCancel = { open(viewer) }).open(viewer)
        }
        set(Paging.SLOT_BACK, Icon.back()) { CurrencyListMenu(eco, viewer).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    /** 가방 속 개수형이 되면 기본 표시를 뗀다 — 기본은 다른 화폐로 넘어간다. */
    private fun keepDefaultValid(def: CurrencyDef): CurrencyDef = if (def.canBeDefault) def else def.copy(isDefault = false)

    private fun amountIcon(material: Material, label: String, value: Long, def: CurrencyDef, hint: String = "") =
        Icon.of(material, "<yellow>" + label + ": <white>" + def.format(value) + "</white></yellow>", listOfNotNull(hint.takeIf { it.isNotEmpty() }?.let { "<gray>$it</gray>" }, "", "<yellow>▶ 클릭: 입력</yellow>"))

    private fun toggle(material: Material, label: String, value: Boolean, help: String) =
        Icon.of(if (value) material else Material.GRAY_DYE, "<yellow>" + label + ": " + Icon.toggle(value) + "</yellow>", listOf("<gray>$help</gray>", "", "<yellow>▶ 클릭: 켜고 끄기</yellow>"))

    companion object {
        const val SIZE = 54
        const val SLOT_PREVIEW = 4
        const val SLOT_NAME = 10
        const val SLOT_UNIT = 11
        const val SLOT_ICON = 12
        const val SLOT_KIND = 14
        const val SLOT_MODE = 15
        const val SLOT_ITEM = 16
        const val SLOT_SCOPE = 19
        const val SLOT_DEFAULT = 20
        const val SLOT_START = 28
        const val SLOT_MAX = 29
        const val SLOT_TRANSFER = 31
        const val SLOT_RANKED = 32
        const val SLOT_CHEQUE = 33
        const val SLOT_DELETE = 43
    }
}

/** 한 플레이어의 잔고(관리). 좌클릭 지급 · 우클릭 차감 · Shift+클릭 설정 — 전부 확인창을 거친다. */
class AccountMenu(eco: Eco, private val viewer: Player, private val target: UUID) :
    Menu(eco, SIZE, Text.renderFlat("<dark_gray>잔고 — " + eco.nameOf(target) + "</dark_gray>")) {

    override fun draw() {
        clear()
        val player = Bukkit.getOfflinePlayer(target)
        for ((slot, def) in Paging.slice(eco.currencies.all(), 0).withIndex()) {
            val balance = if (def.inventory) eco.items.count(player, def) else eco.accounts.balance(target, def)
            set(slot, tile(def, listOf(
                "<gray>잔고: <white>" + def.format(balance) + "</white></gray>",
                if (def.inventory && !player.isOnline) "<dark_gray>가방 속 개수형 — 접속 중일 때만 압니다.</dark_gray>" else "",
                "", "<yellow>▶ 좌클릭: 지급 · 우클릭: 차감</yellow>",
                if (def.inventory) "" else "<yellow>▶ Shift+클릭: 금액 설정</yellow>",
            ).filter { it.isNotEmpty() })) { event ->
                val action = when {
                    event.isShiftClick -> Transfers.AdminAction.SET
                    event.isRightClick -> Transfers.AdminAction.TAKE
                    else -> Transfers.AdminAction.GIVE
                }
                ask(def, action)
            }
        }
        set(SLOT_LEDGER, Icon.of(Material.BOOK, "<aqua>거래 기록</aqua>", listOf("<gray>최근 45건 — 누가·언제·왜·얼마.</gray>", "", "<yellow>▶ 클릭</yellow>"))) {
            val back = { open(viewer) }
            eco.db.run("거래 기록 읽기") {
                val rows = (listOf(eco.db.local) + listOfNotNull(eco.db.network))
                    .flatMap { eco.db.ledger(it, target, Paging.PER_PAGE) }
                    .sortedByDescending { it.at }
                    .take(Paging.PER_PAGE)
                viewer.scheduler.run(eco.plugin, { LedgerMenu(eco, viewer, target, rows, back).open(viewer) }, null)
            }
        }
        set(Paging.SLOT_BACK, Icon.back()) { CurrencyListMenu(eco, viewer).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun ask(def: CurrencyDef, action: Transfers.AdminAction) {
        if (def.inventory && action == Transfers.AdminAction.SET) return
        var asked = false
        // 값을 받으면 확인창을 열고, 취소·잘못 적으면 이 화면으로 — reopen 은 값을 받은 뒤에도 불린다.
        promptAmount(viewer, def.name + " " + action.label, if (action == Transfers.AdminAction.SET) 0 else 1, reopen = { if (!asked) open(viewer) }) { amount ->
            asked = true
            ConfirmMenu(eco, "<yellow>" + eco.nameOf(target) + " · " + def.name + " <white>" + def.format(amount) + "</white> " + action.label + "</yellow>",
                onConfirm = {
                    eco.transfers.admin(viewer, target, def, action, amount)
                    open(viewer)
                },
                onCancel = { open(viewer) }).open(viewer)
        }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_LEDGER = 49
    }
}

/** 거래 기록 45건. 읽기는 DB 스레드에서 끝내고 연다. */
class LedgerMenu(eco: Eco, private val viewer: Player, target: UUID, private val rows: List<LedgerRow>, private val back: () -> Unit) :
    Menu(eco, SIZE, Text.renderFlat("<dark_gray>거래 기록 — " + eco.nameOf(target) + "</dark_gray>")) {

    override fun draw() {
        clear()
        for ((slot, row) in rows.take(Paging.PER_PAGE).withIndex()) {
            val def = eco.currencies.get(row.currency)
            val fmt: (Long) -> String = { def?.format(it) ?: "%,d".format(it) }
            val sign = if (row.delta >= 0) "<green>+" else "<red>-"
            set(slot, Icon.of(if (row.delta >= 0) Material.LIME_DYE else Material.RED_DYE,
                sign + fmt(kotlin.math.abs(row.delta)) + "</" + (if (row.delta >= 0) "green" else "red") + "> <gray>" + (def?.name ?: row.currency) + "</gray>", listOf(
                    "<gray>" + DATE.format(Date(row.at)) + " · " + row.server + "</gray>",
                    "<gray>까닭: <white>" + MiniMessage.miniMessage().escapeTags(row.reason) + "</white></gray>",
                    "<gray>그 뒤 잔고: <white>" + fmt(row.balance) + "</white></gray>",
                )))
        }
        if (rows.isEmpty()) set(SLOT_EMPTY, Icon.of(Material.BARRIER, "<gray>기록이 없습니다</gray>"))
        set(Paging.SLOT_BACK, Icon.back()) { back() }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_EMPTY = 22
        private val DATE = SimpleDateFormat("MM-dd HH:mm:ss")
    }
}
