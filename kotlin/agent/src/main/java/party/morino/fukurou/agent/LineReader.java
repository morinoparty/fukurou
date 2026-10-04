package party.morino.fukurou.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code \n} で区切られた UTF-8 の行を読む（v3 設計 §1.2 の枠）。1 行の長さに上限を付ける。
 *
 * <p>{@code BufferedReader.readLine} は上限なしにメモリを使うので、バイトの単位で自分で区切る。行末の {@code \r} は外す。
 */
final class LineReader {
    /** 1 行の上限のバイト数を超えた。接続は区切りを見失うので閉じるしかない。 */
    static final class LineTooLongException extends IOException {
        private static final long serialVersionUID = 1L;

        LineTooLongException(int maxBytes) {
            super("line exceeds " + maxBytes + " bytes");
        }
    }

    private final InputStream in;
    private final int maxBytes;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;

    /**
     * @param in 読む元（バッファしなくてよい）
     * @param maxBytes 1 行の上限（改行を除くバイト数）
     */
    LineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /**
     * 次の 1 行を読む。
     *
     * @return 行（改行を除く）。ストリームの終わりなら null（終わりの直前の改行の無い行は返す）
     * @throws LineTooLongException 1 行が上限を超えたとき
     */
    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        boolean any = false;
        while (true) {
            if (position == limit) {
                limit = in.read(buffer);
                position = 0;
                if (limit <= 0) {
                    limit = 0;
                    return any ? decode(line) : null;
                }
            }
            any = true;
            int start = position;
            while (position < limit && buffer[position] != '\n') position++;
            int length = position - start;
            if (line.size() + length > maxBytes) throw new LineTooLongException(maxBytes);
            line.write(buffer, start, length);
            if (position < limit) {
                // 改行を読み飛ばす
                position++;
                return decode(line);
            }
        }
    }

    private static String decode(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }
}
