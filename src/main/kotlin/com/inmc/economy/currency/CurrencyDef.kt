package com.inmc.economy.currency

import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/** 잔고가 무엇인가. */
enum class Kind(val id: String, val display: String) {
    /** 숫자만 있는 돈. */
    VIRTUAL("virtual", "가상"),

    /** 실물 아이템(에메랄드·주화…). [ItemMode] 가 어떻게 세는지 정한다. */
    ITEM("item", "실물"),
    ;

    companion object {
        fun of(raw: String?): Kind = entries.firstOrNull { it.id.equals(raw?.trim(), true) } ?: VIRTUAL
    }
}

/** 실물 화폐를 어떻게 세는가. */
enum class ItemMode(val id: String, val display: String) {
    /** 가방에 든 그 아이템의 개수가 곧 잔고. 내면 아이템이 빠지고 받으면 들어온다. 장부가 없다. */
    INVENTORY("inventory", "가방 속 개수"),

    /** 잔고는 장부의 숫자. 아이템으로 넣고(입금) 빼(출금) 바꾼다. */
    BANK("bank", "은행형"),
    ;

    companion object {
        fun of(raw: String?): ItemMode = entries.firstOrNull { it.id.equals(raw?.trim(), true) } ?: BANK
    }
}

/** 어디서 같은 잔고인가. */
enum class Scope(val id: String, val display: String) {
    /** 이 서버에서만. 이 서버의 SQLite. */
    SERVER("server", "서버별"),

    /** 여러 서버가 같은 잔고. 공용 DB(config 의 network). 공용 DB 를 안 적었으면 이 서버의 SQLite. */
    NETWORK("network", "통합"),
    ;

    companion object {
        fun of(raw: String?): Scope = entries.firstOrNull { it.id.equals(raw?.trim(), true) } ?: SERVER
    }
}

/**
 * 화폐 하나의 정의(`currencies.yml`). **금액은 정수다** — 소수점은 헷갈려서 쓰지 않는다.
 *
 * @param max 0 이면 한도 없음.
 * @param item 실물 화폐의 아이템. core [StoredItem] 이라 바닐라(모델 번호 포함)·커스텀아이템(`inmc:…`)·MMOItems 가 다 된다.
 * @param isDefault 기본 화폐 — 금액만 적힌 보상·가격과 Vault 가 쓴다. 가방 속 개수형은 기본이 될 수 없다
 *   (접속 안 한 사람의 잔고를 모르고, 다른 스레드에서 가방을 못 만진다).
 */
data class CurrencyDef(
    val id: String,
    val name: String = id,
    val unit: String = "",
    val icon: Material = Material.GOLD_NUGGET,
    val kind: Kind = Kind.VIRTUAL,
    val mode: ItemMode = ItemMode.BANK,
    val item: StoredItem? = null,
    val scope: Scope = Scope.SERVER,
    val isDefault: Boolean = false,
    val start: Long = 0,
    val max: Long = 0,
    val transferable: Boolean = true,
    val ranked: Boolean = true,
    val cheque: Boolean = false,
    /** 손에 든 것으로 정한 아이콘 — 모델(번호·item_model)·커스텀아이템 모양까지. 없으면 [icon] 재질. */
    val iconItem: StoredItem? = null,
) {
    /** 가방 속 개수가 잔고인 실물 화폐. 장부가 없다. */
    val inventory: Boolean get() = kind == Kind.ITEM && mode == ItemMode.INVENTORY

    /** 은행형 실물 화폐 — 입금·출금이 된다. */
    val bank: Boolean get() = kind == Kind.ITEM && mode == ItemMode.BANK

    /** 기본 화폐가 될 수 있는가. */
    val canBeDefault: Boolean get() = !inventory

    /** "1,000원". */
    fun format(amount: Long): String = "%,d".format(amount) + unit

    fun save(section: ConfigurationSection) {
        section.set("name", name)
        if (unit.isNotEmpty()) section.set("unit", unit)
        section.set("icon", icon.name)
        iconItem?.save(section.createSection("icon-item"))
        section.set("kind", kind.id)
        if (kind == Kind.ITEM) {
            section.set("mode", mode.id)
            item?.save(section.createSection("item"))
        }
        section.set("scope", scope.id)
        if (isDefault) section.set("default", true)
        if (start != 0L) section.set("start", start)
        if (max != 0L) section.set("max", max)
        section.set("transferable", transferable)
        section.set("ranked", ranked)
        section.set("cheque", cheque)
    }

    companion object {
        /** 명령어 인자로 쓰이므로 ASCII 만 — Brigadier 의 `word()` 는 한글에서 멈춘다. */
        val ID = Regex("^[a-z0-9_]{1,32}$")

        fun load(id: String, section: ConfigurationSection): CurrencyDef {
            val kind = Kind.of(section.getString("kind"))
            return CurrencyDef(
                id = id,
                name = section.getString("name")?.takeIf { it.isNotBlank() } ?: id,
                unit = section.getString("unit").orEmpty(),
                icon = section.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.GOLD_NUGGET,
                iconItem = section.getConfigurationSection("icon-item")?.let(StoredItem::load),
                kind = kind,
                mode = ItemMode.of(section.getString("mode")),
                item = section.getConfigurationSection("item")?.let(StoredItem::load),
                scope = Scope.of(section.getString("scope")),
                isDefault = section.getBoolean("default", false),
                start = section.getLong("start", 0).coerceAtLeast(0),
                max = section.getLong("max", 0).coerceAtLeast(0),
                transferable = section.getBoolean("transferable", true),
                ranked = section.getBoolean("ranked", true),
                cheque = section.getBoolean("cheque", false),
            )
        }
    }
}
