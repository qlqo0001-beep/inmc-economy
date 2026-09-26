package com.inmc.economy.currency

import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `currencies.yml`. 파일에 적힌 순서가 화면 순서다.
 *
 * **기본 화폐는 언제나 하나 있다** — 표시가 없거나 여럿이면 장부가 있는 첫 화폐를 쓴다. 금액만 적힌 보상·가격과
 * Vault 가 모두 기본 화폐로 가므로, 없으면 그 전부가 조용히 아무것도 안 한다.
 */
class CurrencyRegistry(io: ConfigService) : YamlFileStore(
    io = io,
    path = listOf(FILE),
    header = """
        화폐. /돈 관리 에서 GUI 로 만들고 고치는 것을 권장합니다. 금액은 전부 정수입니다(소수점 없음).

        id(맨 앞 이름)  영문 소문자·숫자·밑줄 — 명령어에 씁니다. 바꾸면 잔고가 따라가지 않습니다
        name   보이는 이름(MiniMessage)
        unit   금액 뒤에 붙는 글자 — 1,000원
        icon   화면 아이콘(재질). icon-item 은 GUI 에서 손에 든 것으로 정한 모양(모델 번호·커스텀아이템 그대로)
        kind   virtual(숫자만) / item(실물 아이템)
        mode   item 일 때 — inventory(가방에 든 개수가 곧 잔고) / bank(잔고는 숫자, 아이템으로 입금·출금)
        item   item 일 때의 아이템. GUI 에서 손에 든 것으로 정합니다(모델 번호·커스텀아이템 그대로)
        scope  server(이 서버에서만) / network(여러 서버가 같은 잔고 — config.yml 의 network DB)
        default  기본 화폐 — 금액만 적힌 보상·가격과 Vault 가 씁니다. 하나만
        start  처음 받는 금액   max  최대 금액(0 이면 없음)
        transferable  /돈 보내기 허용   ranked  순위에 보임   cheque  수표 허용
    """.trimIndent() + "\n",
    what = "화폐",
) {

    /** 통째로 바꿔 끼운다 — Vault 를 다른 스레드에서 부르는 플러그인이 있어, 읽는 쪽이 고치는 중인 맵을 보면 안 된다. */
    @Volatile
    private var currencies: Map<String, CurrencyDef> = emptyMap()

    /** 지금 읽고 싶을 때(켜질 때). Vault 는 켜지는 그 자리에서 답해야 해서 기다릴 수 없다. */
    fun loadNow() {
        val file = io.file(FILE)
        read(if (file.exists()) io.load(file) else YamlConfiguration())
    }

    /** 리로드 — 파일은 워커에서 읽어 왔다. */
    fun reloadFrom(config: YamlConfiguration) = read(config)

    /** 화면에서 고친 것이 아직 안 써졌으면 지금 쓴다(리로드 직전). 안 고쳤으면 손으로 고친 파일을 덮지 않는다. */
    fun saveIfDirty() {
        if (isDirty()) flushBlocking()
    }

    fun all(): List<CurrencyDef> = currencies.values.toList()

    fun get(id: String?): CurrencyDef? = id?.let { currencies[it.trim().lowercase()] }

    fun default(): CurrencyDef? = pickDefault(currencies.values)

    fun put(def: CurrencyDef) {
        val next = LinkedHashMap(currencies)
        next[def.id] = def
        if (def.isDefault) {
            // 기본은 하나 — 다른 것의 표시를 뗀다.
            for ((id, other) in next.entries.toList()) if (id != def.id && other.isDefault) next[id] = other.copy(isDefault = false)
        }
        currencies = next
        markDirty()
    }

    fun remove(id: String): Boolean {
        val next = LinkedHashMap(currencies)
        val removed = next.remove(id.lowercase()) != null
        if (removed) {
            currencies = next
            markDirty()
        }
        return removed
    }

    override fun read(config: YamlConfiguration) {
        val next = LinkedHashMap<String, CurrencyDef>()
        for (key in config.getKeys(false)) {
            val id = key.lowercase()
            if (!CurrencyDef.ID.matches(id)) {
                io.logger.warning("화폐 id '$key' 는 쓸 수 없습니다(영문 소문자·숫자·밑줄) - 건너뜁니다")
                continue
            }
            config.getConfigurationSection(key)?.let { next[id] = CurrencyDef.load(id, it) }
        }
        currencies = next
    }

    override fun write(config: YamlConfiguration) {
        for (def in currencies.values) def.save(config.createSection(def.id))
    }

    companion object {
        const val FILE = "currencies.yml"

        /** 표시가 붙은 첫 것, 없으면 기본이 될 수 있는 첫 것. */
        fun pickDefault(all: Collection<CurrencyDef>): CurrencyDef? =
            all.firstOrNull { it.isDefault && it.canBeDefault } ?: all.firstOrNull { it.canBeDefault }
    }
}
