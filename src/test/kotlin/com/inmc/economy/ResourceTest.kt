package com.inmc.economy

import com.inmc.economy.config.Messages
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.CurrencyRegistry
import com.inmc.economy.currency.ItemMode
import com.inmc.economy.currency.Kind
import com.inmc.economy.currency.Scope
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 배포 파일과 정의. */
class ResourceTest {

    private fun yaml(path: String): YamlConfiguration =
        javaClass.classLoader.getResourceAsStream(path)!!.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private fun currencies(): List<CurrencyDef> {
        val config = yaml("currencies.yml")
        return config.getKeys(false).map { CurrencyDef.load(it, config.getConfigurationSection(it)!!) }
    }

    @Test
    fun `배포 메시지와 기본값 표의 키가 정확히 같다`() {
        assertEquals(Messages.DEFAULTS.keys, yaml("messages.yml").getKeys(false))
    }

    @Test
    fun `코드가 부르는 메시지 키가 전부 있다`() {
        // 없는 키는 오류 없이 빈 줄이 된다 — 무엇이 실패했는지 플레이어가 모른다.
        val code = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val used = Regex("""send\([^,()]+, (?:if \([^)]*\) )?"([a-z-]+)"(?: else "([a-z-]+)")?""").findAll(code)
            .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.filter { it.isNotEmpty() }.toSet()
        assertTrue(used.size > 20, "키를 못 읽었다: $used")
        assertEquals(emptySet(), used - Messages.DEFAULTS.keys)
    }

    @Test
    fun `채팅 입력이 요구하는 네 키가 있다`() {
        for (key in listOf("prompt-enter", "prompt-cancelled", "prompt-timeout", "prompt-invalid-number")) assertTrue(key in Messages.DEFAULTS)
    }

    @Test
    fun `배포 화폐는 네 가지 모양을 하나씩 보여 주고 기본은 돈 하나다`() {
        val all = currencies()
        assertEquals(listOf("money", "cash", "emerald", "coin"), all.map { it.id })
        assertTrue(all.all { CurrencyDef.ID.matches(it.id) })
        assertEquals("money", CurrencyRegistry.pickDefault(all)?.id)
        assertEquals(1, all.count { it.isDefault })
        val emerald = all.single { it.id == "emerald" }
        assertEquals(Kind.ITEM, emerald.kind)
        assertEquals(ItemMode.INVENTORY, emerald.mode)
        assertEquals(Material.EMERALD, emerald.item?.material)
        assertTrue(all.single { it.id == "coin" }.bank)
        assertEquals(Scope.NETWORK, all.single { it.id == "cash" }.scope)
    }

    @Test
    fun `가방 속 개수형은 기본 화폐가 될 수 없다`() {
        // 접속하지 않은 사람의 잔고를 모르고, Vault 는 다른 스레드에서도 부른다.
        val inventory = CurrencyDef("gem", kind = Kind.ITEM, mode = ItemMode.INVENTORY, isDefault = true)
        val virtual = CurrencyDef("money")
        assertEquals("money", CurrencyRegistry.pickDefault(listOf(inventory, virtual))?.id)
    }

    @Test
    fun `정의가 저장했다 읽어도 그대로다`() {
        val item = StoredItem(kr.inmc.core.item.ItemRef.parse("inmc:금화"), Material.GOLD_NUGGET)
        val def = CurrencyDef("coin", "<yellow>주화", "닢", Material.GOLD_NUGGET, Kind.ITEM, ItemMode.BANK, item, Scope.NETWORK, true, 10, 5000, false, true, true)
        val config = YamlConfiguration()
        def.save(config.createSection("coin"))
        val again = CurrencyDef.load("coin", YamlConfiguration().apply { loadFromString(config.saveToString()) }.getConfigurationSection("coin")!!)
        assertEquals(def.copy(item = null), again.copy(item = null))
        assertEquals(item.ref, again.item?.ref)
    }

    @Test
    fun `금액은 쉼표가 들어간 정수로 보인다`() {
        assertEquals("1,234,567원", CurrencyDef("money", unit = "원").format(1234567))
        assertEquals("0", CurrencyDef("x").format(0))
    }

    @Test
    fun `Vault 로 보인다`() {
        // provides 가 없으면 Vault 를 요구하는 플러그인이 켜지지 않는다.
        val yml = File("src/main/resources/paper-plugin.yml").readText()
        assertTrue(Regex("""(?m)^provides:\s*\[\s*Vault\s*]""").containsMatchIn(yml))
    }
}
