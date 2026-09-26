# inmc-economy

서버의 화폐. 여러 화폐(가상 · 실물 가방형 · 실물 은행형) × 범위(서버별 · 통합) · 수표 · 순위 · 거래 기록.
**Vault 를 대신한다** — Vault API 를 그대로 들고 `provides: [Vault]` 로 "Vault" 로 보인다.

사용법은 `GUIDE.md`. 여기는 설계와 안전장치.

## 다른 플러그인과의 경계

| 쓰는 쪽 | 길 |
|---|---|
| INMC 플러그인의 금액 보상·가격 | core `EconomyHook` → `Currencies.default()` (이 플러그인이 꽂는다). 없으면 Vault |
| INMC 플러그인이 특정 화폐를 쓸 때 | core `Currencies.get("cash")` — 이 플러그인을 몰라도 된다 |
| 남의 플러그인(상점·땅·LuckPerms…) | Vault — 기본 화폐가 `VaultEconomy` 로 보인다 |

`EconomyHook` 은 **부를 때마다 찾는다** — 적재 순서가 보장되지 않아, 켜질 때 한 번 찾으면 우리보다 먼저 켜진
플러그인은 영영 돈을 못 준다.

### Vault 를 들고 가는 것

- `net.milkbowl.vault.*`(Economy·Permission·Chat) 를 **relocate 없이** 셰이딩한다. 남의 플러그인은 그 이름으로 찾는다.
- `paper-plugin.yml` 의 `provides: [Vault]` — Vault 를 `depend` 하는 플러그인이 켜지고, `getPlugin("Vault")` 가 우리를 준다.
- 테스트 서버에서 확인(2026-09-24): Vault jar 없이 LuckPerms `Registered Vault permission & chat hook.`,
  PvPManager `Vault Found! Using it for currency related features`. 옛 방식(plugin.yml) 플러그인이 Paper 방식 플러그인
  안의 Vault 클래스를 본다.
- Vault API 는 LGPL-3.0 — jar 에 `LICENSE-VaultAPI.txt`.
- 진짜 Vault 와 같이 두면 안 된다(이름이 겹친다).

## 돈을 잃거나 복사하지 않게

1. **금액은 `Long`.** 소수점이 없다. Vault 의 Double 은 반올림한다(`fractionalDigits() == 0`).
2. **잔고는 전부 메모리에 있다**([store/Accounts]). Vault 는 메인 스레드에서 접속 안 한 사람의 잔고까지 곧바로 묻는다.
   켜질 때 전부 읽고(그 자리에서 — 한 틱 늦으면 뒤에 켜지는 플러그인이 틀린 답을 받는다), 바꿀 때마다 DB 에 줄 세운다.
3. **바꾸기는 한 자물쇠 안에서.** 다른 스레드에서 Vault 를 부르는 플러그인이 있다. 송금은 두 칸을 **한꺼번에**
   (`transact` — 하나라도 안 되면 아무것도 안 바뀐다) 바꾸고 DB 에도 한 트랜잭션으로 간다.
4. **DB 에는 차이(delta)를 더한다**(`amount = amount + ?`). 여러 서버가 같은 줄을 고쳐도 서로를 덮지 않는다.
   줄이 없을 때만 이 서버가 아는 값으로 만든다 — 시작 금액이 두 번 더해지지 않는다.
5. **쓰기는 한 스레드에서 순서대로**([store/Db]). 풀이 필요 없고 순서가 저절로 지켜진다. 메인은 기다리지 않는다.
   끌 때 줄 선 쓰기를 다 하고 닫는다.
6. **거래 기록**(`eco_ledger`): 언제·누구·화폐·차이·그 뒤 잔고·까닭·서버. 까닭은 `<어디>:<무엇>` —
   `admin:give:<보낸 사람>` · `pay:to:<이름>` · `cheque:redeem:<id>` · `inmc-monster:deposit`(core `EconomyHook`) · `vault:withdraw`.
7. **수표는 id 가 DB 에 있다**(`eco_cheque`). 쓸 때 "아직 안 쓴 것"일 때만 쓴 것으로 적어 복사된 수표는 한 장만 돈이 된다.
   금액은 아이템이 아니라 DB 에서 준다. 잔고 한도에 걸리면 쓴 것을 되돌려 수표를 살린다.

## 통합 화폐(여러 서버)

- 공용 DB 가 있으면 통합 화폐는 거기, 서버별 화폐는 각 서버의 SQLite.
- 다른 서버가 바꾼 줄을 `network.refresh-seconds` 마다 읽어 온다. **이 서버가 아직 DB 에 못 쓴 변경이 있는 칸은
  건너뛴다** — 내 변경 전 값으로 덮지 않게(다음 번에 맞춘다).
- 들어오기 직전(`AsyncPlayerPreLoginEvent`) 그 사람의 줄을 새로 읽는다 — 다른 서버에서 방금 쓴 것.
- 한계: 한 사람이 동시에 두 서버에 있을 수 없으므로 대부분 안전하지만, 서버를 옮기는 순간 옛 서버의 쓰기가
  아직 줄에 있으면(밀리초) 새 서버가 옛 값을 볼 수 있다. DB 는 차이를 더하므로 결국 맞는다.

## 실물 화폐

- **가방 속 개수형**: 가방(단축바 포함, 방어구·왼손 제외)을 센다. 장부가 없어 거래 기록만. 가방은 그 사람을 가진
  스레드에서만 만진다 — 다른 스레드에서 오면 거절(PlaceholderAPI 는 마지막으로 센 값). 접속하지 않은 사람에게 준 것은
  `eco_pending` 에 적고 다음 접속 때 준다(화폐가 지워졌으면 버리지 않고 다시 적어 둔다).
- **은행형**: 잔고는 장부. 입금은 가방에서 빼고 더하기(한도에 걸리면 돌려준다), 출금은 빼고 아이템 주기.
- 아이템은 core `StoredItem` — `ItemResolver.capture` 가 바닐라·모델 번호(스냅샷)·커스텀아이템·MMOItems 를 가린다.

## 파일

| 파일 | 무엇 |
|---|---|
| `config.yml` | 서버 이름 · 공용 DB · 수표 모양 · 순위 주기 |
| `currencies.yml` | 화폐 정의(GUI 가 쓴다) |
| `messages.yml` | 메시지 |
| `economy.db` | 잔고 · 거래 기록 · 수표 · 받을 실물 (SQLite, WAL) |

JDBC 드라이버는 들고 가지 않는다 — Paper 가 `sqlite-jdbc` 와 `mysql-connector-j` 를 서버 라이브러리로 갖고 있다.
