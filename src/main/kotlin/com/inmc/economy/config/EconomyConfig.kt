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
        )
    }
}
