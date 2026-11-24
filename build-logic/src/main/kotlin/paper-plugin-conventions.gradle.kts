// 서버에 올라가는 플러그인 모듈 공통: plugin.yml 치환, Shadow JAR, 로컬 테스트 서버
plugins {
    id("java-conventions")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

val mcVersion: String by project

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to mcVersion,
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    // Shadow 가 만든 JAR 만 배포용으로 사용
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // common 을 플러그인마다 다른 패키지로 옮겨서, 여러 플러그인이 서로 다른 common 버전을 써도 충돌하지 않게 함
    val pkg = project.name.replace("-", "")
    relocate("kr.commata.common", "kr.commata.$pkg.libs.common")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    // 운영 서버와 같은 버전의 Paper 를 내려받아 실행 (서버 파일은 <모듈>/run/ 에 생성)
    minecraftVersion(mcVersion)
    jvmArgs("-Xms1G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}
