plugins {
    id("paper-plugin-conventions")
}

repositories {
    // 구 Vault API (net.milkbowl.vault.economy.Economy). VaultUnlocked 가 같은 인터페이스를 그대로 제공
    maven("https://jitpack.io") {
        name = "jitpack"
        content { includeGroup("com.github.MilkBowl") }
    }
}

dependencies {
    // 서버에 설치된 VaultUnlocked(plugin.yml 이름 Vault)가 제공하므로 JAR 에 넣지 않음
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
    // Paper 서버 JAR 에 sqlite-jdbc 가 들어 있으므로 JAR 에 넣지 않음 (네이티브 라이브러리 중복 로드 방지)
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}
