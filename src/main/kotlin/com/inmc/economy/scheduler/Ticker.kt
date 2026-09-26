package com.inmc.economy.scheduler

import com.inmc.economy.Eco
import kr.inmc.core.scheduler.TickerBase

/**
 * 1초에 한 번. 화폐 정의 저장 · 채팅 입력 만료 · 순위 · 다른 서버가 바꾼 통합 화폐 읽기.
 *
 * 통합 화폐 읽기는 DB 스레드에서 한다([com.inmc.economy.store.Db.run]) — 메인은 기다리지 않는다.
 * 공용 DB 가 없으면(서버 하나) 읽을 것이 없다.
 */
class Ticker(private val eco: Eco) : TickerBase(eco.plugin) {

    override val periodTicks = 20L

    private var seconds = 0L

    /** 마지막으로 읽은 줄의 시각. 그 뒤로 바뀐 것만 다시 읽는다. */
    @Volatile
    private var networkSeen = System.currentTimeMillis()

    override fun ready(): Boolean = eco.ready

    override fun tick(now: Long) {
        seconds++
        step("prompts") { eco.prompts.tick(now) }
        step("flush") { eco.currencies.flush() }
        if (seconds % eco.config.rankRefreshSeconds == 0L || seconds == 1L) step("ranks") { eco.ranks.refresh() }
        val network = eco.db.network
        if (network != null && seconds % eco.config.networkRefreshSeconds == 0L) {
            step("network") {
                val since = networkSeen
                eco.db.run("통합 화폐 읽기") {
                    val rows = eco.db.changedSince(network, since)
                    if (rows.isEmpty()) return@run
                    networkSeen = rows.maxOf { it.updated }
                    val ids = eco.currencies.all().filter { it.scope == com.inmc.economy.currency.Scope.NETWORK }.map { it.id }.toSet()
                    eco.accounts.external(rows.filter { it.currency in ids })
                }
            }
        }
    }
}
