// サーバー内エージェント FukurouAgent（Paper のプラグイン、v3 設計 §1）。公開しない。jar はコアの jar のリソースになる
plugins {
    java
}

version = rootProject.version

/** コンパイルに使う Paper の API（1.20.4 が下限。新しい版でもバイナリで動くように書く）。 */
val paperApi = "io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT"

dependencies {
    // Paper が実行時に持っている（Gson と Adventure もここから来る）
    compileOnly(paperApi)
    // jar に入れる（下の tasks.jar）
    implementation(project(":agent-api"))

    // 単体テストはサーバーを起動しないが、instanceof の相手のクラス（Entity・Component など）を読めるようにする
    testImplementation(paperApi)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Paper 1.20.4（Java 17）から 26.x（Java 25）まで同じ jar で動かす
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.jar {
    // agent-api のクラスを jar に入れる（コンパニオンプラグインは depend でこのクラスを見る）
    val bundled = configurations.runtimeClasspath
    dependsOn(bundled)
    from(bundled.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}
