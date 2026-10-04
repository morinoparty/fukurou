package party.morino.fukurou.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** execute の戻り値の変換（v3 設計 §1.4、§1.5）。 */
class OperationsTest {
    /** 要素を読もうとすると投げるリスト（変換器の中での失敗を起こす）。 */
    static final class Unconvertible extends AbstractList<Object> {
        @Override
        public Object get(int index) {
            throw new IllegalStateException("no element");
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    @DisplayName("converts task return values with the shared converter")
    void convertsValues() throws AgentException {
        assertEquals(JsonParser.parseString("[1,2]"), Operations.convertTaskValue(List.of(1, 2)));
    }

    @Test
    @DisplayName("a value that cannot be converted is internal, not task_failed")
    void conversionFailureIsInternal() {
        AgentException failure = assertThrows(AgentException.class, () -> Operations.convertTaskValue(new Unconvertible()));
        assertEquals(ErrorType.INTERNAL, failure.type);
        assertEquals(IllegalStateException.class, failure.remote.getClass());
    }

    @Test
    @DisplayName("unknown entity types and keys get the minecraft namespace")
    void normalizeKey() {
        assertEquals("minecraft:zombie", Operations.normalizeKey(" Zombie "));
        assertEquals("myplugin:thing", Operations.normalizeKey("MyPlugin:Thing"));
    }
}
