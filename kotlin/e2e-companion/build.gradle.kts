// fukurou 自身の e2e / agentTest が使うコンパニオンプラグイン FukurouE2eCompanion（v3 設計 §1.5）。公開しない。
// jar のパスは e2eTest / agentTest に fukurou.plugin.companion として渡す（ルートの build.gradle.kts）
plugins {
    java
}

version = rootProject.version

dependencies {
    // Paper が実行時に持っている（Gson もここから来る）
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    // FukurouAgent の jar が実行時に持っている。shade してはいけない（別の登録簿になる）
    compileOnly(project(":agent-api"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") { expand("version" to version) }
}
