package party.morino.fukurou.player

/** クライアントの視点。F5 を押すたびにこの順で巡回する。 */
public enum class Perspective {
    /** 一人称。 */
    FIRST_PERSON,

    /** 三人称（背面）。 */
    THIRD_PERSON_BACK,

    /** 三人称（正面）。 */
    THIRD_PERSON_FRONT,
}
