package com.inmc.economy.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/** `config.yml` 의 불변 스냅샷. 리로드는 통째로 바꿔 끼운다. */
data class EconomyConfig(
    /** 거래 기록에 남는 이 서버의 이름 — 여러 서버가 한 DB 를 쓸 때 어디서 일어났는지. */
    val serverName: String = "main",
    val networkUrl: String = "",
    val networkUser: String = "",
    val networkPassword: String = "",
    /** 다른 서버가 바꾼 통합 화폐를 몇 초마다 읽어 올지. */
    val networkRefreshSeconds: Int = 10,
    val chequeMaterial: Material = Material.PAPER,
    val chequeModel: Int = 0,
    val rankRefreshSeconds: Int = 30,
    /** 송금·수표 발행에 붙는 수수료. 끄면 0원이다. */
    val feeEnabled: Boolean = true,
    /** 켜져 있을 때의 기본 비율(%). 올림 계산이라 1원에도 1원이 붙는다. */
    val feePercent: Double = 5.0,
) {
    companion object {
        fun from(config: YamlConfiguration): EconomyConfig = EconomyConfig(
            serverName = config.getString("server-name")?.takeIf { it.isNotBlank() } ?: "main",
            networkUrl = config.getString("network.url").orEmpty().trim(),
            networkUser = config.getString("network.user").orEmpty(),
            networkPassword = config.getString("network.password").orEmpty(),
            networkRefreshSeconds = config.getInt("network.refresh-seconds", 10).coerceIn(2, 600),
            chequeMaterial = config.getString("cheque.material")?.let { Material.matchMaterial(it) } ?: Material.PAPER,
            chequeModel = config.getInt("cheque.custom-model-data", 0).coerceAtLeast(0),
            rankRefreshSeconds = config.getInt("rank.refresh-seconds", 30).coerceIn(5, 3600),
            feeEnabled = config.getBoolean("fees.enabled", true),
            feePercent = config.getDouble("fees.percent", 5.0).coerceIn(0.0, 100.0),
        )

        /**
         * 수수료(원). 꺼져 있거나 비율이 0 이하면 0원. 올림이라 소액도 최소 1원이다.
         * 서버 없이 돈다.
         */
        fun feeFor(amount: Long, enabled: Boolean, percent: Double): Long {
            if (!enabled || percent <= 0.0 || amount <= 0L) return 0L
            return kotlin.math.ceil(amount * percent / 100.0).toLong().coerceAtLeast(1L)
        }
    }

    /** 이 금액에 붙는 수수료. */
    fun feeFor(amount: Long): Long = feeFor(amount, feeEnabled, feePercent)
}
