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
    // 공통 유틸 (메시지, 시간 표기, GUI 기반, AtomicFiles, PlayerDatReader). Shadow 로 플러그인 패키지 안에 옮겨 넣음
    implementation(project(":common"))
    // 한 트랜잭션 양방향 멱등 정산 API (TRADE.md 4장). 런타임에 Bank 플러그인이 제공하므로 JAR 에 넣지 않음
    compileOnly(project(":plugins:bank"))
    // 서버의 VaultUnlocked(plugin.yml 이름 Vault)가 제공하므로 JAR 에 넣지 않음
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
}
