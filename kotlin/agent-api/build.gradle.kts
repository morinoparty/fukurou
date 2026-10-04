// エージェントの API（FukurouTask / TaskContext / FukurouTasks）。公開しない。
// クラスはエージェントの jar とコアの jar の両方に入る（v3 設計 §1.1、V5）
plugins {
    `java-library`
}

version = rootProject.version

java {
    // コアの sources jar に入れるために作る
    withSourcesJar()
}
// Paper 1.20.4（Java 17）でも読めるバイトコードにする
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}
