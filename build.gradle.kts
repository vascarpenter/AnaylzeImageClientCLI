import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.gradleup.shadow") version "8.3.5"   // 旧ID: com.github.johnrengelman.shadow 8.1.1
}

group = "local.tool"
version = "1.0"

repositories {
    mavenCentral()
}

// 追加の外部依存は不要。HTTP / JSON / ファイル I/O はすべて JDK 標準実装のみ [1]
dependencies {
}

application {
    // Main.kt のトップレベル main → コンパイル後クラス名は MainKt [1]
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(17)   // java.net.http.HttpClient を使っているため 11 以上、17 推奨 [1]
}

tasks.named<JavaExec>("run") {
    // CLI なので stdout / stderr をそのままコンソールへ
    standardOutput = System.out
    errorOutput = System.err

    // ./gradlew run -Pdir=/data/images -PcliArgs="--format openai" でも渡せるように
    val dir = providers.gradleProperty("dir").orNull
    val extra = providers.gradleProperty("cliArgs").orNull
    if (dir != null) {
        args = listOf(dir) + (extra?.split(" ")?.filter { it.isNotBlank() } ?: emptyList())
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "MainKt")
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("analyze-image-cli")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "MainKt",
            "Implementation-Version" to project.version
        )
    }
}