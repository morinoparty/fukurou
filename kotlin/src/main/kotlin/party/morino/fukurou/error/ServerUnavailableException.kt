package party.morino.fukurou.error

/** サーバーが落ちた、または RCON に届かない。以降のテストは skipped になる。 */
public class ServerUnavailableException(message: String) : FukurouException(message)
