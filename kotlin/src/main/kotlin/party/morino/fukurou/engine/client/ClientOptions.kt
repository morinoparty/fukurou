package party.morino.fukurou.engine.client

/** クライアントの options.txt（runner/client.py:12-25 の CLIENT_OPTIONS をそのまま）。 */
internal object ClientOptions {
    /**
     * 初回起動の案内画面やポーズを抑止し、GUI の大きさを固定するクライアント設定。
     * ソフトウェアレンダリングの複数クライアントとサーバーが CPU を取り合うため、FPS も制限する。
     */
    const val TEXT: String = "maxFps:30\n" +
        "enableVsync:false\n" +
        "guiScale:2\n" +
        "fullscreen:false\n" +
        "onboardAccessibility:false\n" +
        "skipMultiplayerWarning:true\n" +
        "joinedFirstServer:true\n" +
        "tutorialStep:none\n" +
        "pauseOnLostFocus:false\n" +
        "lang:en_us\n" +
        "renderDistance:4\n" +
        "soundCategory_master:0.0\n"

    /** クライアントの画面の大きさ（Xvfb の画面と同じ）。 */
    const val RESOLUTION: String = "1280x720"
}
