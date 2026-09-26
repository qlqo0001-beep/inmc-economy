package com.inmc.economy.gui

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.util.Ph
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 이 플러그인의 화면. 리로드 때 열린 화면을 닫는 청소가 [owner] 로 우리 것을 가려낸다. */
abstract class Menu(protected val eco: Eco, size: Int, title: Component) : kr.inmc.core.gui.Menu(size, title) {

    override val owner: Any get() = eco

    /** 화폐의 아이콘 — 실물 화폐면 그 아이템 그대로(모델 번호까지), 아니면 정한 아이콘(손에 든 것의 모양 또는 재질). */
    protected fun iconOf(def: CurrencyDef): ItemStack =
        def.item?.takeIf { def.kind == com.inmc.economy.currency.Kind.ITEM }?.let { eco.resolver.create(it, 1) } ?: plainIcon(def)

    /** 실물과 상관없이 정한 아이콘. */
    protected fun plainIcon(def: CurrencyDef): ItemStack =
        def.iconItem?.let { eco.resolver.create(it, 1) } ?: ItemStack(def.icon)

    /** 정수 금액을 묻는다. 소수점·음수는 받지 않는다(소수점은 헷갈려서 쓰지 않는다). */
    protected fun promptAmount(viewer: Player, label: String, min: Long, reopen: () -> Unit, onValue: (Long) -> Unit) {
        Editors.promptText(
            eco.prompts, viewer, label,
            listOf("<gray>정수로 적으세요. 쉼표는 빼도 되고 넣어도 됩니다(1,000).</gray>"),
            reopen = reopen,
        ) { raw ->
            val value = raw.replace(",", "").replace(" ", "").toLongOrNull()
            if (value == null || value < min) {
                eco.messages.send(viewer, "invalid-amount")
                return@promptText
            }
            onValue(value)
        }
    }

    protected fun tile(def: CurrencyDef, lore: List<String>): ItemStack = Icon.annotate(iconOf(def), def.name, lore)

    protected fun ph(def: CurrencyDef): Ph = Ph.of().currency(def.name)
}
