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
    api(libs.kotlinx.serialization.json)
    // JUnit は suspend のテストメソッドを kotlin-reflect で呼ぶ。無いと最初の `@Test suspend fun` が失敗するので POM で持ち込む
    runtimeOnly(libs.kotlin.reflect)
    // JUnit は利用側が持ち込む（6.0 以上）
    compileOnly(libs.junit.jupiter.api.min)
    // FukurouPlanListener
    compileOnly(libs.junit.platform.launcher.min)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // 拡張の一連の流れを入れ子のランチャーで確かめる（GameServerExtensionLifecycleTest）
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    useJUnitPlatform()
    // 偽のサーバーを使うテストクラスは GameServerExtensionLifecycleTest が入れ子のランチャーで実行する
    exclude("party/morino/fukurou/junit/fixture/**")
    // result.json の契約（../schema/result.v2.json）で出力と ../ui/fixtures を検証する
    systemProperty("fukurou.schemaDir", rootDir.resolve("../schema").absolutePath)
    // スキーマや fixture だけを変えたときもテストをやり直す
    inputs.dir(rootDir.resolve("../schema")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("resultSchema")
    inputs.dir(rootDir.resolve("../ui/fixtures/artifacts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uiFixtures")
}

val generateVersion = tasks.register<WriteProperties>("generateVersion") {
    destinationFile.set(layout.buildDirectory.file("generated/version/META-INF/fukurou/version.properties"))
    property("version", project.version.toString())
    property("portablemc", "5.0.4")
}
// 生成した version.properties をリソースに含める（META-INF/fukurou/ の 3 階層上がリソースのルート）
sourceSets.main { resources.srcDir(generateVersion.map { it.destinationFile.get().asFile.parentFile.parentFile.parentFile }) }

// Kotlin の CLI（v3 設計 V9）。versions アクションが ./gradlew -q fukurouCli --args="versions 1.21.6- --output m.json" で使う
tasks.register<JavaExec>("fukurouCli") {
    group = "application"
    description = "Runs the fukurou command line (versions <spec> [--max-versions N] [--paper-channel C] [--output FILE])."
    // main の runtimeClasspath はエージェントの jar（generateAgentResource）を含み、それをビルドするには
    // repo.papermc.io の paper-api が要る。版の解決には要らないので、クラス・version.properties・依存だけにする
    classpath = files(
        sourceSets.main.map { it.output.classesDirs },
        generateVersion.map { it.destinationFile.get().asFile.parentFile.parentFile.parentFile },
        configurations.runtimeClasspath,
    )
    mainClass.set("party.morino.fukurou.cli.FukurouCli")
}

// ---- agent-plugin: サーバー内エージェントの埋め込み（v3 設計 §1.1、V11）ここから ----
// エージェント（kotlin/agent）と API（kotlin/agent-api）は公開しないサブプロジェクト。成果物はコアの jar に入れ、
// POM / Gradle module には現れないよう、公開する構成（api / implementation など）とは別の構成で解決する
/** サブプロジェクトの jar（runtimeElements）を 1 つだけ解決する構成を作る。 */
fun embeddedJarConfiguration(name: String) = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
val fukurouAgentJar = embeddedJarConfiguration("fukurouAgentJar")
val fukurouAgentApiJar = embeddedJarConfiguration("fukurouAgentApiJar")
val fukurouAgentApiSources = configurations.create("fukurouAgentApiSources") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
    }
}
dependencies {
    fukurouAgentJar(project(":agent"))
    fukurouAgentApiJar(project(":agent-api"))
    fukurouAgentApiSources(project(":agent-api"))
}

// エージェントの jar を party/morino/fukurou/engine/agent/fukurou-agent.jar のリソースにする。
// jar だけでなく main のリソースのディレクトリにも置くので、e2eTest / agentTest（クラスのディレクトリで動く）からも読める
val generateAgentResource = tasks.register<Sync>("generateAgentResource") {
    from(fukurouAgentJar) { rename { "fukurou-agent.jar" } }
    into(layout.buildDirectory.dir("generated/agent/party/morino/fukurou/engine/agent"))
}
// party/morino/fukurou/engine/agent/ の 5 階層上がリソースのルート
sourceSets.main {
    resources.srcDir(generateAgentResource.map { it.destinationDir.parentFile.parentFile.parentFile.parentFile.parentFile })
}

// agent-api のクラス（Java 17 のバイトコード）とソースをそのままコアの jar / sources jar に入れる。
// 利用者のコンパニオンプラグインは com.github.morinoparty:fukurou を compileOnly にしてコンパイルできる
tasks.jar {
    from(fukurouAgentApiJar.elements.map { jars -> jars.map { zipTree(it) } }) {
        include("party/morino/fukurou/agent/api/**")
    }
}
tasks.named<Jar>("sourcesJar") {
    from(fukurouAgentApiSources.elements.map { jars -> jars.map { zipTree(it) } }) {
        include("party/morino/fukurou/agent/api/**")
    }
}

// e2eTest / agentTest の JVM にコンパニオンプラグイン（kotlin/e2e-companion）の jar のパスを渡す。
// agentTest のスイートが後から登録されても効くよう、名前で絞る
val fukurouCompanionJar = embeddedJarConfiguration("fukurouCompanionJar")
dependencies { fukurouCompanionJar(project(":e2e-companion")) }
tasks.withType<Test>().matching { it.name in setOf("e2eTest", "agentTest") }.configureEach {
    // 入力にすると jar のタスクへの依存も付く
    inputs.files(fukurouCompanionJar).withPropertyName("fukurouCompanionJar").withNormalizer(ClasspathNormalizer::class)
    val companion = fukurouCompanionJar
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dfukurou.plugin.companion=${companion.singleFile.absolutePath}") })
}
// ---- agent-plugin: ここまで ----

// CI 専用の e2e（Xvfb が要る）。check には含めない
testing.suites.register<JvmTestSuite>("e2eTest") {
    useJUnitJupiter(libs.versions.junit.asProvider())
    dependencies {
        implementation(project())
    }
    targets.configureEach {
        testTask.configure {
            // -Pfukurou.* をすべて -D としてテストの JVM に渡す
            val forwarded = providers.gradlePropertiesPrefixedBy("fukurou.")
            jvmArgumentProviders.add(CommandLineArgumentProvider { forwarded.get().map { (k, v) -> "-D$k=$v" } })
            maxParallelForks = 1
            // 毎回サーバーを起動して結果を書く。UP-TO-DATE にもビルドキャッシュからの復元（FROM-CACHE）にもしない
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}

// サーバー内エージェントの結合テスト（本物の Paper を起動する。プレイヤーは使わないので Xvfb は要らない）。check には含めない
testing.suites.register<JvmTestSuite>("agentTest") {
    useJUnitJupiter(libs.versions.junit.asProvider())
    dependencies {
        implementation(project())
        // execute の引数と戻り値（@Serializable）。コアは implementation で持つので、ここでも宣言する
        implementation(libs.kotlinx.serialization.json)
    }
    targets.configureEach {
        testTask.configure {
            // -Pfukurou.* をすべて -D としてテストの JVM に渡す
            val forwarded = providers.gradlePropertiesPrefixedBy("fukurou.")
            jvmArgumentProviders.add(CommandLineArgumentProvider { forwarded.get().map { (k, v) -> "-D$k=$v" } })
            maxParallelForks = 1
            // 毎回サーバーを起動して結果を書く。UP-TO-DATE にもビルドキャッシュからの復元（FROM-CACHE）にもしない
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "fukurou"
        from(components["java"])
    }
}
