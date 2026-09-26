package com.inmc.economy.store

import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.Scope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 장부가 있는 화폐(가상·은행형)의 잔고. **전부 메모리에 있다** — Vault 는 메인 스레드에서 접속 안 한 사람의 잔고까지
 * 곧바로 묻는데 그때마다 DB 를 읽을 수는 없다. 켜질 때 전부 읽고, 바꿀 때마다 DB 에 차이를 줄 세운다([Db.apply]).
 *
 * 바꾸기는 한 자물쇠 안에서 한다. 다른 스레드에서 Vault 를 부르는 플러그인이 있고, 송금은 두 칸을 **한꺼번에**
 * 바꿔야 한다(하나만 바뀌면 돈이 사라지거나 생긴다). 메모리 연산이라 자물쇠를 오래 쥐지 않는다.
 */
class Accounts(private val db: Db) {

    /** 바꿀 것 하나. */
    data class Op(val player: UUID, val def: CurrencyDef, val delta: Long, val reason: String)

    private val lock = Any()

    private val balances = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>>()

    /** DB 에 아직 안 닿은 변경 수. 통합 화폐를 맞출 때 이 칸은 건너뛴다 — 내 변경 전의 값으로 덮지 않게. */
    private val pending = ConcurrentHashMap<String, AtomicInteger>()

    private fun key(player: UUID, currency: String) = "$player|$currency"

    /** 잔고. 처음 보는 사람이면 시작 금액. */
    fun balance(player: UUID, def: CurrencyDef): Long = balances[player]?.get(def.id) ?: def.start

    /** 이 화폐의 잔고를 가진 사람 전부(순위). */
    fun holders(currency: String): Map<UUID, Long> =
        balances.entries.mapNotNull { (player, map) -> map[currency]?.let { player to it } }.toMap()

    /** 켜질 때. */
    fun load(rows: List<BalanceRow>) {
        synchronized(lock) {
            for (row in rows) balances.getOrPut(row.player) { ConcurrentHashMap() }[row.currency] = row.amount
        }
    }

    /**
     * 다른 서버가 바꾼 통합 화폐. 이 서버가 아직 DB 에 못 쓴 변경이 있는 칸은 건너뛴다 — 그 변경이 닿은 뒤 다음 번에 맞춘다.
     * @return 바뀐 칸 수.
     */
    fun external(rows: List<BalanceRow>): Int = synchronized(lock) {
        var changed = 0
        for (row in rows) {
            if ((pending[key(row.player, row.currency)]?.get() ?: 0) > 0) continue
            val map = balances.getOrPut(row.player) { ConcurrentHashMap() }
            if (map.put(row.currency, row.amount) != row.amount) changed++
        }
        changed
    }

    /** 더하거나 뺀다. 0 아래로·최대 위로 가면 아무것도 안 하고 null. */
    fun add(player: UUID, def: CurrencyDef, delta: Long, reason: String): Long? =
        transact(listOf(Op(player, def, delta, reason)))?.first()

    /** 정확히 이 금액으로. */
    fun set(player: UUID, def: CurrencyDef, value: Long, reason: String): Long? = synchronized(lock) {
        add(player, def, value - balance(player, def), reason)
    }

    /**
     * 여러 칸을 **한꺼번에** — 하나라도 안 되면 아무것도 안 바꾼다. DB 에도 한 트랜잭션으로 간다(화폐가 같은 DB 일 때).
     * @return 바꾼 뒤의 잔고들(순서대로), 안 되면 null.
     */
    fun transact(ops: List<Op>): List<Long>? = synchronized(lock) {
        val after = LinkedHashMap<String, Long>()
        val results = ArrayList<Long>(ops.size)
        for (op in ops) {
            if (op.def.inventory) return null
            val k = key(op.player, op.def.id)
            val current = after[k] ?: balance(op.player, op.def)
            val next = runCatching { Math.addExact(current, op.delta) }.getOrNull() ?: return null
            if (next < 0) return null
            if (op.def.max > 0 && next > op.def.max && op.delta > 0) return null
            after[k] = next
            results += next
        }
        val now = System.currentTimeMillis()
        val changes = HashMap<Boolean, MutableList<Change>>()
        for ((index, op) in ops.withIndex()) {
            balances.getOrPut(op.player) { ConcurrentHashMap() }[op.def.id] = results[index]
            if (op.delta == 0L) continue
            changes.getOrPut(op.def.scope == Scope.NETWORK) { ArrayList() } +=
                Change(op.player, op.def.id, op.delta, results[index], op.reason, now)
        }
        for ((network, list) in changes) {
            val keys = list.map { key(it.player, it.currency) }
            for (k in keys) pending.getOrPut(k) { AtomicInteger() }.incrementAndGet()
            db.run("잔고 " + list.size + "건") {
                try {
                    db.apply(db.source(network), list)
                } finally {
                    for (k in keys) pending[k]?.decrementAndGet()
                }
            }
        }
        results
    }
}
