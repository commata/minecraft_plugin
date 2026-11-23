// 모든 모듈(common, 플러그인) 공통: Java 버전, 인코딩, 저장소, Paper API
plugins {
    java
}

val paperApiVersion: String by project
val javaVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // 서버가 제공하므로 JAR 에 포함하지 않음
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8" // Windows 한글 깨짐 방지
    options.release.set(javaVersion.toInt())
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
