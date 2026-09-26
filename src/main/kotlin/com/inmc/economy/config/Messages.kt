package com.inmc.economy.config

import com.inmc.economy.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌. 읽고 보내는 부분은 core 의 [MessageCatalog] 가 갖고 있고 여기는 기본값 표뿐이다.
 * `ResourceTest` 가 배포 파일과 이 표의 키가 정확히 같은지, 코드가 부르는 키가 전부 있는지 지킨다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#ffe259:#ffa751>[ 돈 ]</gradient> ",

            // --- 공통 ---------------------------------------------------------------
            "player-only" to "<red>플레이어만 쓸 수 있습니다.</red>",
            "player-not-found" to "<red>'{player}' 을(를) 찾을 수 없습니다. 한 번이라도 접속한 사람만 됩니다.</red>",
            "unknown-currency" to "<red>'{currency}' 라는 화폐가 없습니다.</red>",
            "invalid-amount" to "<red>금액은 1 이상의 정수로 적어주세요.</red>",
            "invalid-id" to "<red>화폐 id 는 영문 소문자·숫자·밑줄만 됩니다: {value}</red>",
            "already-exists" to "<red>'{value}' 은(는) 이미 있습니다.</red>",
            "hand-empty" to "<red>손에 아이템을 들고 있어야 합니다.</red>",
            "no-permission" to "<red>권한이 없습니다.</red>",
            "not-enough" to "<red>{currency}<red> 이(가) 모자랍니다. 필요: <white>{amount}</white></red>",
            "over-max" to "<red>{currency}<red> 의 최대 금액을 넘습니다.</red>",
            "reloaded" to "<green>설정을 다시 불러왔습니다. 화폐 {count}개.</green>",

            // --- 잔고 ---------------------------------------------------------------
            "balance-header" to "<gold>{player} 의 잔고</gold>",
            "balance-line" to "<gray> - {currency}<gray>: <white>{amount}</white></gray>",
            "rank-header" to "<gold>{currency} <gold>순위</gold>",
            "rank-line" to "<gray> {count}위 <white>{player}</white> - {amount}</gray>",

            // --- 송금 ---------------------------------------------------------------
            "pay-not-allowed" to "<red>{currency}<red> 은(는) 보낼 수 없는 화폐입니다.</red>",
            "pay-self" to "<red>자신에게는 보낼 수 없습니다.</red>",
            "pay-over-max" to "<red>{player} 의 {currency}<red> 이(가) 최대 금액을 넘어 보낼 수 없습니다.</red>",
            "paid" to "<green>{player} 에게 {currency}<green> <white>{amount}</white> 을(를) 보냈습니다. 남은 잔고 <white>{balance}</white></green>",
            "received" to "<green>{player} 에게서 {currency}<green> <white>{amount}</white> 을(를) 받았습니다. 잔고 <white>{balance}</white></green>",

            // --- 관리자 -------------------------------------------------------------
            "admin-done" to "<green>{player} · {currency}<green> <white>{amount}</white> {value} 완료. 잔고 <white>{balance}</white></green>",
            "admin-failed" to "<red>{player} · {currency}<red> {amount} {value} 실패 — 잔고가 모자라거나 최대 금액을 넘습니다(가방 속 개수형은 접속 중이어야 합니다).</red>",
            "inventory-no-set" to "<red>{currency}<red> 은(는) 가방 속 개수형이라 금액을 정할 수 없습니다. 지급·차감을 쓰세요.</red>",

            // --- 실물 화폐 ----------------------------------------------------------
            "bank-deposited" to "<green>{currency}<green> <white>{amount}</white> 을(를) 입금했습니다. 잔고 <white>{balance}</white></green>",
            "bank-nothing" to "<yellow>입금할 {currency}<yellow> 이(가) 가방에 없거나 최대 금액입니다.</yellow>",
            "bank-withdrew" to "<green>{currency}<green> <white>{amount}</white> 을(를) 꺼냈습니다. 잔고 <white>{balance}</white></green>",
            "not-bank" to "<red>{currency}<red> 은(는) 은행형 실물 화폐가 아닙니다.</red>",
            "pending-delivered" to "<green>접속하지 않은 동안 받은 {currency}<green> <white>{amount}</white> 을(를) 가방에 넣었습니다.</green>",

            // --- 수표 ---------------------------------------------------------------
            "cheque-not-allowed" to "<red>{currency}<red> 은(는) 수표로 꺼낼 수 없는 화폐입니다.</red>",
            "cheque-issued" to "<green>{currency}<green> <white>{amount}</white> 수표를 발행했습니다.</green>",
            "cheque-failed" to "<red>수표를 발행하지 못했습니다. 금액은 돌려드렸습니다.</red>",
            "cheque-unknown-currency" to "<red>이 수표의 화폐({currency})가 더 이상 없습니다.</red>",
            "cheque-used" to "<red>이미 쓴 수표입니다.</red>",
            "cheque-redeemed" to "<green>수표 {currency}<green> <white>{amount}</white> 을(를) 잔고에 넣었습니다.</green>",

            // --- 도움말 -------------------------------------------------------------
            "help" to listOf(
                "<gold>/돈</gold> <gray>- 내 지갑</gray>",
                "<gold>/돈 보기 [플레이어]</gold> <gray>- 잔고</gray>",
                "<gold>/돈 보내기 <플레이어> <금액> [화폐]</gold> <gray>- 송금</gray>",
                "<gold>/돈 순위 [화폐]</gold>",
                "<gold>/돈 입금 <화폐> [수량]</gold> <gray>· </gray><gold>/돈 출금 <화폐> <수량></gold> <gray>- 은행형 실물 화폐</gray>",
                "<gold>/돈 수표 <금액> [화폐]</gold>",
                "<red>/돈 지급·차감·설정 <플레이어> <금액> [화폐]</red> <gray>· </gray><red>/돈 관리</red> <gray>· </gray><red>/돈 리로드</red>",
            ).joinToString("\n"),

            // --- 채팅 입력(core ChatPrompt 가 요구한다) -------------------------------
            "prompt-enter" to "<yellow>채팅으로 값을 입력하세요. <gray>(취소: 취소)</gray></yellow>",
            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 지났습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자를 입력해주세요.</red>",
        )
    }
}
