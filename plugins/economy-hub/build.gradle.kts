plugins {
    id("paper-plugin-conventions")
}

// VJobs 는 공개 Maven 저장소가 없어서 컴파일 의존성 없이 리플렉션으로 이벤트·API 를 씀 (jobs/VJobsHook)

repositories {
    // 구 Vault API (net.milkbowl.vault.economy.Economy). VaultUnlocked 가 같은 인터페이스를 그대로 제공
    maven("https://jitpack.io") {
        name = "jitpack"
        content { includeGroup("com.github.MilkBowl") }
    }
}

dependencies {
    // 메뉴 공통 기반 (kr.commata.common.gui), Shadow 로 플러그인 패키지 안에 옮겨 넣음
    implementation(project(":common"))
    // 잔액 표시용. 서버의 VaultUnlocked(plugin.yml 이름 Vault)가 제공하므로 JAR 에 넣지 않음
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        isTransitive = false
    }
}
