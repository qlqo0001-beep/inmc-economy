package com.inmc.economy.store

import kr.inmc.core.store.SqlDialect
import kr.inmc.core.store.SqlSource
import kr.inmc.core.store.SqlWorker
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/** 잔고 한 줄. */
data class BalanceRow(val player: UUID, val currency: String, val amount: Long, val updated: Long)

/**
 * 잔고를 바꾼 것 하나.
 *
 * @param balance 바꾼 뒤의 잔고(이 서버가 아는 값). **DB 에는 [delta] 를 더한다** — 여러 서버가 같은 줄을 고쳐도
 *   서로를 덮지 않게. 줄이 아직 없을 때만 이 값으로 만든다.
 * @param ledgerOnly 가방 속 개수형 실물 화폐 — 장부가 없어 거래 기록만 남긴다.
 */
data class Change(
    val player: UUID,
    val currency: String,
    val delta: Long,
    val balance: Long,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
    val ledgerOnly: Boolean = false,
)

data class LedgerRow(val at: Long, val player: UUID, val currency: String, val delta: Long, val balance: Long, val reason: String, val server: String)

data class Cheque(val id: UUID, val currency: String, val amount: Long, val issuer: UUID, val issuedAt: Long)

data class Pending(val id: Long, val currency: String, val amount: Long)

/**
 * 화폐의 저장소. 쓰기는 전부 **한 스레드**에서 순서대로(core [SqlWorker]) — 송금처럼 둘이 같이 바뀌는 것은 한 트랜잭션으로.
 *
 * 메인 스레드는 기다리지 않는다([run]). 기다리는 것([call])은 켜질 때와 접속 직전(비동기 사건)뿐이다.
 */
class Db(logger: Logger, private val server: String) : SqlWorker(logger, "inmc-economy-db") {

    fun open(file: File, networkUrl: String = "", networkUser: String = "", networkPassword: String = "") =
        open(file, networkUrl, networkUser, networkPassword, ::schema)

    // --- 아래는 전부 쓰기 스레드에서 ---------------------------------------------------------------

    private fun schema(source: SqlSource) {
        val sqlite = source.dialect == SqlDialect.SQLITE
        val text = if (sqlite) "TEXT" else "VARCHAR(64)"
        val big = if (sqlite) "INTEGER" else "BIGINT"
        val autoId = if (sqlite) "id INTEGER PRIMARY KEY AUTOINCREMENT" else "id BIGINT AUTO_INCREMENT PRIMARY KEY"
        source.connection().createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS eco_balance (player $text NOT NULL, currency $text NOT NULL, amount $big NOT NULL, updated $big NOT NULL, PRIMARY KEY (player, currency))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS eco_ledger ($autoId, at $big NOT NULL, player $text NOT NULL, currency $text NOT NULL, " +
                    "delta $big NOT NULL, balance $big NOT NULL, reason ${if (sqlite) "TEXT" else "VARCHAR(191)"} NOT NULL, server $text NOT NULL" +
                    (if (sqlite) ")" else ", INDEX eco_ledger_player (player, at))"),
            )
            if (sqlite) st.execute("CREATE INDEX IF NOT EXISTS eco_ledger_player ON eco_ledger (player, at)")
            st.execute(
                "CREATE TABLE IF NOT EXISTS eco_cheque (id $text PRIMARY KEY, currency $text NOT NULL, amount $big NOT NULL, " +
                    "issuer $text NOT NULL, issued_at $big NOT NULL, redeemed_by $text, redeemed_at $big)",
            )
            st.execute("CREATE TABLE IF NOT EXISTS eco_pending ($autoId, player $text NOT NULL, currency $text NOT NULL, amount $big NOT NULL, at $big NOT NULL)")
        }
    }

    fun balances(source: SqlSource, player: UUID? = null): List<BalanceRow> {
        val sql = "SELECT player, currency, amount, updated FROM eco_balance" + (if (player != null) " WHERE player = ?" else "")
        return source.connection().prepareStatement(sql).use { ps ->
            if (player != null) ps.setString(1, player.toString())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        add(BalanceRow(id, rs.getString(2), rs.getLong(3), rs.getLong(4)))
                    }
                }
            }
        }
    }

    /** 다른 서버가 바꾼 줄 — [since] 이후. 통합 화폐를 맞추는 데 쓴다. */
    fun changedSince(source: SqlSource, since: Long): List<BalanceRow> =
        source.connection().prepareStatement("SELECT player, currency, amount, updated FROM eco_balance WHERE updated > ?").use { ps ->
            ps.setLong(1, since)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        add(BalanceRow(id, rs.getString(2), rs.getLong(3), rs.getLong(4)))
                    }
                }
            }
        }

    /** 바꾼 것들을 **한 트랜잭션으로** — 송금의 빼기와 더하기가 따로 남지 않게. */
    fun apply(source: SqlSource, changes: List<Change>) {
        if (changes.isEmpty()) return
        val connection = source.connection()
        val upsert = if (source.dialect == SqlDialect.SQLITE) {
            "INSERT INTO eco_balance (player, currency, amount, updated) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (player, currency) DO UPDATE SET amount = eco_balance.amount + ?, updated = ?"
        } else {
            "INSERT INTO eco_balance (player, currency, amount, updated) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE amount = amount + ?, updated = ?"
        }
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(upsert).use { ps ->
                for (change in changes) {
                    if (change.ledgerOnly) continue
                    ps.setString(1, change.player.toString())
                    ps.setString(2, change.currency)
                    ps.setLong(3, change.balance)
                    ps.setLong(4, change.at)
                    ps.setLong(5, change.delta)
                    ps.setLong(6, change.at)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.prepareStatement("INSERT INTO eco_ledger (at, player, currency, delta, balance, reason, server) VALUES (?, ?, ?, ?, ?, ?, ?)").use { ps ->
                for (change in changes) {
                    ps.setLong(1, change.at)
                    ps.setString(2, change.player.toString())
                    ps.setString(3, change.currency)
                    ps.setLong(4, change.delta)
                    ps.setLong(5, change.balance)
                    ps.setString(6, change.reason.take(180))
                    ps.setString(7, server)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.commit()
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    fun ledger(source: SqlSource, player: UUID, limit: Int): List<LedgerRow> =
        source.connection().prepareStatement(
            "SELECT at, player, currency, delta, balance, reason, server FROM eco_ledger WHERE player = ? ORDER BY id DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, player.toString())
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(LedgerRow(rs.getLong(1), player, rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7)))
                }
            }
        }

    fun issueCheque(source: SqlSource, cheque: Cheque) {
        source.connection().prepareStatement("INSERT INTO eco_cheque (id, currency, amount, issuer, issued_at) VALUES (?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, cheque.id.toString())
            ps.setString(2, cheque.currency)
            ps.setLong(3, cheque.amount)
            ps.setString(4, cheque.issuer.toString())
            ps.setLong(5, cheque.issuedAt)
            ps.executeUpdate()
        }
    }

    /**
     * 수표를 쓴 것으로 적는다. **아직 안 쓴 수표일 때만** — 복사된 수표를 두 번 쓸 수 없게.
     * @return 이번에 쓴 수표면 그 정보, 이미 썼거나 없는 수표면 null.
     */
    fun redeemCheque(source: SqlSource, id: UUID, by: UUID, at: Long): Cheque? {
        val connection = source.connection()
        val changed = connection.prepareStatement("UPDATE eco_cheque SET redeemed_by = ?, redeemed_at = ? WHERE id = ? AND redeemed_by IS NULL").use { ps ->
            ps.setString(1, by.toString())
            ps.setLong(2, at)
            ps.setString(3, id.toString())
            ps.executeUpdate()
        }
        if (changed != 1) return null
        return connection.prepareStatement("SELECT currency, amount, issuer, issued_at FROM eco_cheque WHERE id = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Cheque(id, rs.getString(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getLong(4))
            }
        }
    }

    /** 쓴 것을 되돌린다 — 잔고 한도에 걸려 돈을 못 넣었을 때 수표를 살린다. */
    fun unredeemCheque(source: SqlSource, id: UUID) {
        source.connection().prepareStatement("UPDATE eco_cheque SET redeemed_by = NULL, redeemed_at = NULL WHERE id = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeUpdate()
        }
    }

    fun addPending(source: SqlSource, player: UUID, currency: String, amount: Long) {
        source.connection().prepareStatement("INSERT INTO eco_pending (player, currency, amount, at) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, player.toString())
            ps.setString(2, currency)
            ps.setLong(3, amount)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    /** 접속하지 않은 동안 받을 실물 화폐. 읽으면서 지운다(한 트랜잭션). */
    fun takePending(source: SqlSource, player: UUID): List<Pending> {
        val connection = source.connection()
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val rows = connection.prepareStatement("SELECT id, currency, amount FROM eco_pending WHERE player = ? ORDER BY id").use { ps ->
                ps.setString(1, player.toString())
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Pending(rs.getLong(1), rs.getString(2), rs.getLong(3))) } }
            }
            if (rows.isNotEmpty()) {
                connection.prepareStatement("DELETE FROM eco_pending WHERE player = ?").use { ps ->
                    ps.setString(1, player.toString())
                    ps.executeUpdate()
                }
            }
            connection.commit()
            return rows
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            throw t
        } finally {
            connection.autoCommit = autoCommit
        }
    }
}
