plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.economy"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-economy"
}

dependencies {
    compileOnly(libs.placeholderapi) { isTransitive = false }

    // Vault API 를 **그대로**(relocate 없이) 들고 간다. paper-plugin.yml 의 `provides: [Vault]` 와 함께
    // 이 플러그인이 곧 Vault 로 보이게 하는 것 — 다른 플러그인은 `net.milkbowl.vault.*` 이름으로 찾으므로
    // 옮기면 아무도 못 찾는다. Vault API 는 LGPL-3.0 이다(jar 안의 LICENSE-VaultAPI.txt).
    implementation(libs.vault.api) { isTransitive = false }

    // JDBC 드라이버는 들고 가지 않는다 — Paper 가 sqlite-jdbc 와 mysql-connector-j 를 서버 라이브러리로 갖고 있다.
    // 테스트는 서버 없이 도므로 따로 붙인다.
    testImplementation(libs.sqlite.jdbc)
}
