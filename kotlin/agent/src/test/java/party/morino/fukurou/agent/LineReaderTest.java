package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 1 行 1 メッセージの枠（v3 設計 §1.2）。 */
class LineReaderTest {
    private static LineReader reader(String text, int max) {
        return new LineReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), max);
    }

    @Test
    @DisplayName("splits UTF-8 lines on newline and strips a trailing carriage return")
    void splitsLines() throws IOException {
        LineReader reader = reader("{\"a\":\"あ\"}\n\r\nlast\r\n", 1024);
        assertEquals("{\"a\":\"あ\"}", reader.readLine());
        assertEquals("", reader.readLine());
        assertEquals("last", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    @DisplayName("returns a final line without newline and then null")
    void finalLineWithoutNewline() throws IOException {
        LineReader reader = reader("one\ntwo", 1024);
        assertEquals("one", reader.readLine());
        assertEquals("two", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    @DisplayName("reads lines larger than the internal buffer and split across reads")
    void largeLines() throws IOException {
        String big = "x".repeat(100_000);
        // 1 バイトずつしか返さないストリームでも区切れる
        InputStream trickle = new InputStream() {
            private final byte[] bytes = (big + "\n" + "y\n").getBytes(StandardCharsets.UTF_8);
            private int position;

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (position >= bytes.length) return -1;
                buffer[offset] = bytes[position++];
                return 1;
            }
        };
        LineReader reader = new LineReader(trickle, 200_000);
        assertEquals(big, reader.readLine());
        assertEquals("y", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    @DisplayName("a line exactly at the limit is accepted and one byte more is rejected")
    void limit() throws IOException {
        assertEquals("abcd", reader("abcd\n", 4).readLine());
        assertThrows(LineReader.LineTooLongException.class, () -> reader("abcde\n", 4).readLine());
        assertThrows(LineReader.LineTooLongException.class, () -> reader("abcde", 4).readLine());
    }

    @Test
    @DisplayName("the protocol caps lines at 16 MiB")
    void protocolCap() {
        assertEquals(16 * 1024 * 1024, Protocol.MAX_LINE_BYTES);
    }
}
