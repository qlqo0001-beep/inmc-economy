package com.inmc.economy.verify

import com.inmc.economy.Eco
import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.ItemMode
import com.inmc.economy.currency.Kind
import com.inmc.economy.gui.CurrencyListMenu
import com.inmc.economy.gui.WalletMenu
import com.inmc.economy.store.Accounts
import com.inmc.economy.util.Ph
import kr.inmc.core.economy.Currencies
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `/돈 관리 검증` — 서버 안에서 실제 장부·화면·다리를 돌려 확인한다(드랍·상점 검증기와 같은 방식, 2026-10-08).
 *
 * - 검증하는 사람의 **기본 화폐 잔고**로 더했다 빼서 끝에는 그대로다. 장부에는 `verify:…` 사유가 남는다(그게 진짜 거래의 증거다).
 * - DB 를 거쳐 비동기로 끝나는 것(수표 발행→사용, 네트워크 화폐 동기화)은 돌리지 않는다 — 수표는 아이템을 만들고 되읽는 데까지만.
 * - 두 사람이 필요한 송금은 자기 자신에게 보내 거절되는지만 본다.
 */
class Verifier(private val eco: Eco) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Stage) -> String?)

    fun run(player: Player) {
        val stage = Stage(eco, player)
        val results = CHECKS.map { check ->
            val failure = try {
                check.run(stage)
            } catch (t: Throwable) {
                "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            Result(check.name, failure)
        }
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        eco.messages.send(
            player, "verify-done",
            Ph.of().amount((results.size - failures.size - skips.size).toString()).count(failures.size)
                .value(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) eco.messages.send(player, "verify-failure", Ph.of().value("${f.name} — ${f.failure}"))
        for (s in skips) eco.messages.send(player, "verify-skipped", Ph.of().value("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = eco.io.file("verify", "economy-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-economy 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        eco.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        eco.messages.send(player, "verify-report", Ph.of().value("plugins/${eco.plugin.name}/verify/${file.name}"))
    }

    /** 검사들이 쓰는 무대 — 사람과 검사용 화폐. */
    class Stage(val eco: Eco, val player: Player) {
        /** 장부 검사에 쓰는 화폐 — 기본 화폐가 가방 속 개수형이면 다른 은행형. 없으면 null(건너뜀). */
        val def: CurrencyDef? = eco.currencies.default()?.takeIf { !it.inventory } ?: eco.currencies.all().firstOrNull { !it.inventory }

        fun balance(def: CurrencyDef): Long = eco.accounts.balance(player.uniqueId, def)

        /** 최대 금액을 안 넘는 시험 금액. 넣을 틈이 없으면 null. */
        fun delta(def: CurrencyDef): Long? {
            val room = if (def.max > 0) def.max - balance(def) else 1000L
            return if (room <= 0) null else minOf(1000L, room)
        }
    }

    companion object {
        const val SKIP = "건너뜀:"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("화폐 정의 — 하나 이상 · 기본 화폐 · id 는 ASCII") { s ->
                val all = s.eco.currencies.all()
                ok(all.isNotEmpty(), "화폐가 하나도 없습니다")
                    ?: ok(s.eco.currencies.default() != null, "기본 화폐가 없습니다")
                    ?: all.firstOrNull { !it.id.matches(Regex("[a-z0-9_-]+")) }?.let { "id '${it.id}' 가 ASCII 가 아닙니다" }
            },
            Check("지급 → 잔고 반영 → 차감 → 원래대로") { s ->
                val def = s.def ?: return@Check "$SKIP 은행형 화폐가 없습니다"
                val before = s.balance(def)
                val delta = s.delta(def) ?: return@Check "$SKIP 잔고가 최대라 더할 틈이 없습니다"
                val up = s.eco.accounts.add(s.player.uniqueId, def, delta, "verify:add")
                ok(up == before + delta, "더한 뒤 $up (${before + delta} 여야)")
                    ?: run {
                        val down = s.eco.accounts.add(s.player.uniqueId, def, -delta, "verify:take")
                        ok(down == before, "뺀 뒤 $down ($before 여야)")
                    }
            },
            Check("잔고보다 많이 빼면 거절되고 잔고는 그대로") { s ->
                val def = s.def ?: return@Check "$SKIP 은행형 화폐가 없습니다"
                val before = s.balance(def)
                val result = s.eco.accounts.add(s.player.uniqueId, def, -(before + 1), "verify:overdraw")
                ok(result == null, "거절돼야 하는데 $result") ?: ok(s.balance(def) == before, "잔고가 ${s.balance(def)} 로 바뀌었다")
            },
            Check("여러 칸 한꺼번에 — 하나가 안 되면 전부 그대로") { s ->
                val def = s.def ?: return@Check "$SKIP 은행형 화폐가 없습니다"
                val before = s.balance(def)
                val delta = s.delta(def) ?: return@Check "$SKIP 잔고가 최대라 더할 틈이 없습니다"
                val result = s.eco.accounts.transact(
                    listOf(
                        Accounts.Op(s.player.uniqueId, def, delta, "verify:batch"),
                        Accounts.Op(s.player.uniqueId, def, -(before + delta + 1), "verify:batch"),
                    ),
                )
                ok(result == null, "거절돼야 하는데 $result") ?: ok(s.balance(def) == before, "잔고가 ${s.balance(def)} 로 바뀌었다")
            },
            Check("core 화폐 다리 — Currencies.get 의 잔고가 장부와 같다") { s ->
                val def = s.def ?: return@Check "$SKIP 은행형 화폐가 없습니다"
                val bridge = Currencies.get(def.id) ?: return@Check "core 에 '${def.id}' 가 등록돼 있지 않습니다"
                ok(Currencies.default() != null, "core 기본 화폐가 없습니다")
                    ?: ok(bridge.balance(s.player) == s.balance(def), "다리 잔고 ${bridge.balance(s.player)} ≠ 장부 ${s.balance(def)}")
            },
            Check("수표 — 만든 수표 아이템을 다시 알아본다") { s ->
                val def = s.eco.currencies.all().firstOrNull { it.cheque && !it.inventory } ?: return@Check "$SKIP 수표를 켠 화폐가 없습니다"
                val cheque = com.inmc.economy.store.Cheque(UUID.randomUUID(), def.id, 100L, s.player.uniqueId, System.currentTimeMillis())
                val item = s.eco.cheques.item(def, cheque)
                ok(s.eco.cheques.idOf(item) == cheque.id, "수표 아이템에서 id 를 못 읽었습니다")
                    ?: ok(s.eco.cheques.idOf(org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER)) == null, "맨 종이를 수표로 봅니다")
            },
            Check("실물 화폐(가방 속 개수형) — 주고 세고 거둔다") { s ->
                val def = s.eco.currencies.all().firstOrNull { it.kind == Kind.ITEM && it.mode == ItemMode.INVENTORY }
                    ?: return@Check "$SKIP 가방 속 개수형 화폐가 없습니다"
                val before = s.eco.items.count(s.player, def)
                ok(s.eco.items.give(s.player, def, 3, "verify:give"), "주기가 실패했습니다")
                    ?: ok(s.eco.items.count(s.player, def) == before + 3, "준 뒤 ${s.eco.items.count(s.player, def)} (${before + 3} 여야)")
                    ?: ok(s.eco.items.take(s.player, def, 3, "verify:take"), "거두기가 실패했습니다")
                    ?: ok(s.eco.items.count(s.player, def) == before, "거둔 뒤 ${s.eco.items.count(s.player, def)} ($before 여야)")
            },
            Check("자기 자신에게 송금은 거절되고 잔고는 그대로") { s ->
                val def = s.def?.takeIf { it.transferable } ?: return@Check "$SKIP 보낼 수 있는 은행형 화폐가 없습니다"
                val before = s.balance(def)
                ok(!s.eco.transfers.pay(s.player, s.player.uniqueId, def, 10L), "자기 자신에게 보내졌습니다")
                    ?: ok(s.balance(def) == before, "잔고가 ${s.balance(def)} 로 바뀌었다")
            },
            Check("PAPI — %inmceco_balance% 가 값을 준다") { s ->
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return@Check "$SKIP PlaceholderAPI 가 없습니다"
                // PAPI 클래스는 리플렉션으로 — 없는 서버에서 이 검사 목록이 적재될 때 터지지 않게.
                val value = runCatching {
                    Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", org.bukkit.OfflinePlayer::class.java, String::class.java)
                        .invoke(null, s.player, "%inmceco_balance%") as? String
                }.getOrNull()
                ok(!value.isNullOrBlank() && !value.contains("%inmceco_balance%"), "balance 가 풀리지 않았습니다: $value")
            },
            Check("지갑·화폐 관리 화면이 열린다") { s ->
                WalletMenu(s.eco, s.player).open(s.player)
                val wallet = s.player.openInventory.topInventory.holder is WalletMenu
                CurrencyListMenu(s.eco, s.player).open(s.player)
                val admin = s.player.openInventory.topInventory.holder is CurrencyListMenu
                s.player.closeInventory()
                ok(wallet, "지갑 화면이 안 열렸습니다") ?: ok(admin, "화폐 관리 화면이 안 열렸습니다")
            },
        )
    }
}
