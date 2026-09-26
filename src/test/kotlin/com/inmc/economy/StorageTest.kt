package com.inmc.economy

import com.inmc.economy.currency.CurrencyDef
import com.inmc.economy.currency.Scope
import com.inmc.economy.store.Accounts
import com.inmc.economy.store.Change
import com.inmc.economy.store.Cheque
import com.inmc.economy.store.Db
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 저장소 — 실제 SQLite 파일로. 돈은 조용히 틀리면 안 되는 것이라 서버 없이 DB 까지 확인한다. */
class StorageTest {

    private val dir: File = Files.createTempDirectory("inmc-economy").toFile()
    private val opened = ArrayList<Db>()

    private fun db(server: String = "test"): Db = Db(Logger.getLogger("test"), server).also {
        it.open(File(dir, "economy.db"))
        opened += it
    }

    @AfterTest
    fun close() {
        opened.forEach { runCatching { it.shutdown() } }
        dir.deleteRecursively()
    }

    private val money = CurrencyDef("money", unit = "원", isDefault = true)
    private val player = UUID.randomUUID()

    @Test
    fun `두 서버가 같은 줄을 고쳐도 서로를 덮지 않는다`() {
        // 서버 A 는 +100 (줄이 없어 100 으로 만든다), 서버 B 는 옛 값(0)을 보고 +50 — DB 에는 둘 다 더해져야 한다.
        val a = db("a")
        val b = db("b")
        a.call { a.apply(a.local, listOf(Change(player, "money", 100, 100, "a"))) }
        b.call { b.apply(b.local, listOf(Change(player, "money", 50, 50, "b"))) }
        val row = a.call { a.balances(a.local, player) }.single()
        assertEquals(150, row.amount)
        val ledger = a.call { a.ledger(a.local, player, 10) }
        assertEquals(listOf("b", "a"), ledger.map { it.server }, "거래 기록에 어느 서버인지 남는다")
    }

    @Test
    fun `수표는 한 번만 쓴다`() {
        val db = db()
        val id = UUID.randomUUID()
        db.call { db.issueCheque(db.local, Cheque(id, "money", 5000, player, 1L)) }
        val first = db.call { db.redeemCheque(db.local, id, player, 2L) }
        val again = db.call { db.redeemCheque(db.local, id, player, 3L) }
        assertNotNull(first)
        assertEquals(5000, first.amount, "금액은 아이템이 아니라 DB 에서")
        assertNull(again, "복사된 수표는 두 번째부터 쓸 수 없다")
        db.call { db.unredeemCheque(db.local, id) }
        assertNotNull(db.call { db.redeemCheque(db.local, id, player, 4L) }, "한도에 걸려 되돌린 수표는 다시 쓸 수 있다")
        assertNull(db.call { db.redeemCheque(db.local, UUID.randomUUID(), player, 5L) }, "없는 수표")
    }

    @Test
    fun `접속하지 않은 동안 받은 실물은 한 번만 꺼내진다`() {
        val db = db()
        db.call { db.addPending(db.local, player, "emerald", 10); db.addPending(db.local, player, "emerald", 5) }
        assertEquals(listOf(10L, 5L), db.call { db.takePending(db.local, player) }.map { it.amount })
        assertTrue(db.call { db.takePending(db.local, player) }.isEmpty())
    }

    @Test
    fun `모자라거나 최대를 넘으면 아무것도 안 바뀐다`() {
        val db = db()
        val accounts = Accounts(db)
        val capped = money.copy(max = 1000)
        assertEquals(1000, accounts.add(player, capped, 1000, "t"))
        assertNull(accounts.add(player, capped, 1, "t"), "최대 금액 위로")
        assertNull(accounts.add(player, capped, -1001, "t"), "0 아래로")
        assertEquals(1000, accounts.balance(player, capped))
        assertEquals(0, accounts.add(player, capped, -1000, "t"))
        assertNull(accounts.add(player, capped, Long.MIN_VALUE, "t"), "넘침은 거절")
    }

    @Test
    fun `송금은 둘 다 되거나 둘 다 안 된다`() {
        val db = db()
        val accounts = Accounts(db)
        val other = UUID.randomUUID()
        accounts.add(player, money, 300, "t")
        assertNull(accounts.transact(listOf(Accounts.Op(player, money, -500, "pay"), Accounts.Op(other, money, 500, "pay"))))
        assertEquals(300, accounts.balance(player, money))
        assertEquals(0, accounts.balance(other, money), "보내는 쪽이 모자라면 받는 쪽도 안 바뀐다")
        assertEquals(listOf(100L, 200L), accounts.transact(listOf(Accounts.Op(player, money, -200, "pay"), Accounts.Op(other, money, 200, "pay"))))
    }

    @Test
    fun `메모리와 DB 가 같아진다 — 처음 받는 금액도`() {
        val db = db()
        val accounts = Accounts(db)
        val starter = money.copy(start = 1000)
        accounts.add(player, starter, -300, "t")
        accounts.add(player, starter, 50, "t")
        val rows = db.call { db.balances(db.local, player) }
        assertEquals(750, rows.single().amount, "시작 금액 1000 에서 -300 +50")
        assertEquals(750, accounts.balance(player, starter))
    }

    @Test
    fun `다른 서버가 바꾼 통합 화폐를 받아 들이되 아직 안 쓴 내 변경은 덮지 않는다`() {
        val db = db()
        val accounts = Accounts(db)
        val cash = CurrencyDef("cash", scope = Scope.NETWORK)
        val other = UUID.randomUUID()
        accounts.add(player, cash, 100, "t")
        db.call { } // 줄 선 쓰기가 끝나게
        val changed = accounts.external(listOf(
            com.inmc.economy.store.BalanceRow(player, "cash", 999, 1L),
            com.inmc.economy.store.BalanceRow(other, "cash", 70, 1L),
        ))
        assertEquals(2, changed)
        assertEquals(999, accounts.balance(player, cash))
        assertEquals(70, accounts.balance(other, cash))
    }
}
