import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.github.morinoparty"
// JitPack はタグ名を VERSION 環境変数で渡す。手元では dev
version = System.getenv("VERSION") ?: "dev"

java {
    // ツールチェーンは指定しない（JitPack の openjdk21 でも手元の JDK 25 でも、foojay なしでビルドできるように）
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // JDK 22 以降の API（FFM など）を使えないようにする
        freeCompilerArgs.add("-Xjdk-release=21")
        // 利用側の Kotlin が 2.2 以上ならメタデータを読めるようにする
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        // ライブラリ自身は SPI を使うので、オプトインを求めない
        optIn.add("party.morino.fukurou.spi.FukurouSpi")
    }
}

dependencies {
    // suspend の API と Deferred がシグネチャに出る
    api(libs.kotlinx.coroutines.core)
    // Audience / Component / Sound / BossBar がシグネチャに出る
    api(libs.adventure.api)
    // Key がシグネチャに出る
    api(libs.adventure.key)
    // Component → コマンドの JSON
    implementation(libs.adventure.gson)
    // Component → チャットの照合に使うプレーンテキスト
    implementation(libs.adventure.plain)
    // result.json、Paper / GitHub の API
    implementation(libs.kotlinx.serialization.json)
    // JUnit は利用側が持ち込む（6.0 以上）
    compileOnly(libs.junit.jupiter.api.min)
    // FukurouPlanListener
    compileOnly(libs.junit.platform.launcher.min)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    useJUnitPlatform()
    // result.json の契約（../schema/result.v2.json）で出力を検証する
    systemProperty("fukurou.schemaDir", rootDir.resolve("../schema").absolutePath)
    // pydantic との突き合わせに使う golden を書き出す場所
    systemProperty("fukurou.goldenDir", layout.buildDirectory.dir("contract").get().asFile.absolutePath)
}

val generateVersion = tasks.register<WriteProperties>("generateVersion") {
    destinationFile.set(layout.buildDirectory.file("generated/version/META-INF/fukurou/version.properties"))
    property("version", project.version.toString())
    property("portablemc", "5.0.4")
}
// 生成した version.properties をリソースに含める（META-INF/fukurou/ の 3 階層上がリソースのルート）
sourceSets.main { resources.srcDir(generateVersion.map { it.destinationFile.get().asFile.parentFile.parentFile.parentFile }) }

// CI 専用の e2e（Xvfb が要る）。check には含めない
testing.suites.register<JvmTestSuite>("e2eTest") {
    useJUnitJupiter(libs.versions.junit.asProvider())
    dependencies {
        implementation(project())
        implementation(libs.kotlin.reflect)
    }
    targets.configureEach {
        testTask.configure {
            // -Pfukurou.* をすべて -D としてテストの JVM に渡す
            val forwarded = providers.gradlePropertiesPrefixedBy("fukurou.")
            jvmArgumentProviders.add(CommandLineArgumentProvider { forwarded.get().map { (k, v) -> "-D$k=$v" } })
            maxParallelForks = 1
            outputs.upToDateWhen { false }
        }
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "fukurou"
        from(components["java"])
    }
}
