// 依存は基本的に Maven Central。エージェントのサブプロジェクト（kotlin/agent、kotlin/e2e-companion）が
// compileOnly で使う Paper の API だけは repo.papermc.io から取る（content のフィルタで、そのグループ以外は探しに行かない）。
// どちらも公開する成果物の依存には現れないので、JitPack の利用側は Maven Central だけで解決できる
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content {
                includeGroup("io.papermc.paper")
                // paper-api 1.20.4 の推移的な依存（bungeecord-chat の deprecated ビルド）は papermc にしか無い
                includeGroup("net.md-5")
            }
        }
    }
}
rootProject.name = "fukurou"

// サーバー内エージェント（v3 設計 §1）。どれも公開しない。agent と agent-api の成果物はコアの jar に入れる
include("agent-api", "agent", "e2e-companion")
