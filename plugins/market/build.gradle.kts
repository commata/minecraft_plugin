plugins {
    id("paper-plugin-conventions")
}

repositories {
    // 구 Vault API (net.milkbowl.vault.economy.Economy). 잔액 조회에 사용
    maven("https://jitpack.io") {
        name = "jitpack"
        content { includeGroup("com.github.MilkBowl") }
    }
}

dependencies {
    // 메뉴 공통 기반 (kr.commata.common.gui), Shadow 로 플러그인 패키지 안에 옮겨 넣음
    implementation(project(":common"))
    // 멱등 거래 API (ECONOMY.md 13.6). 런타임에 Bank 플러그인이 제공하므로 JAR 에 넣지 않음
    compileOnly(project(":plugins:bank"))
    // 서버의 VaultUnlocked(plugin.yml 이름 Vault)가 제공하므로 JAR 에 넣지 않음
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
    // Paper 서버 JAR 에 sqlite-jdbc 가 들어 있으므로 JAR 에 넣지 않음
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}
