package cl.duoc.pedidos360.messaging;

import java.io.Reader;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import static org.assertj.core.api.Assertions.*;

/** Small char-backed input verifies early name limits, without exhausting memory. */
class JacksonSecurityRegressionTests {
    @Test void charBackedNameLimitStopsBeforeConsumingWholeName() {
        String input = "{\"" + "x".repeat(16384) + "\":0}";
        var source = new CountingReader(input);
        var factory = JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNameLength(64).build()).build();
        assertThatThrownBy(() -> {
            try (var parser = factory.createParser(source)) {
                while (parser.nextToken() != null) { }
            }
        }).isInstanceOf(StreamConstraintsException.class);
        assertThat(source.position).isLessThan(16384);
    }
    static final class CountingReader extends Reader {
        final String input; int position;
        CountingReader(String input) { this.input = input; }
        @Override public int read(char[] buffer, int offset, int length) {
            if (position == input.length()) return -1;
            int size = Math.min(length, input.length() - position);
            input.getChars(position, position + size, buffer, offset); position += size; return size;
        }
        @Override public void close() { }
    }
}
