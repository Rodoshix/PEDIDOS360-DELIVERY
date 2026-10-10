package cl.duoc.pedidos360.messaging;

import com.rabbitmq.client.impl.ValueReader;
import com.rabbitmq.client.impl.ValueWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Bounded decoder regressions; no broker, oversized allocation or credentials. */
class AmqpClientSecurityRegressionTests {
    @Test void malformedShortstrCanBeReencodedWithinProtocolLimit() throws Exception {
        byte[] wire = new byte[256];
        wire[0] = (byte) 255;
        Arrays.fill(wire, 1, wire.length, (byte) 0xff);
        String decoded = new ValueReader(new DataInputStream(new ByteArrayInputStream(wire))).readShortstr();
        assertThat(decoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        var output = new ByteArrayOutputStream();
        new ValueWriter(new DataOutputStream(output)).writeShortstr(decoded);
        assertThat(output.size()).isLessThanOrEqualTo(256);
    }

    @Test void nestedTablesAreRejectedAtABoundedDepth() throws Exception {
        byte[] table = new byte[4]; // empty table
        for (int i = 0; i < 40; i++) {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(3 + table.length);
            out.writeByte(1); out.writeByte('x'); out.writeByte('F'); out.write(table);
            table = bytes.toByteArray();
        }
        var reader = new ValueReader(new DataInputStream(new ByteArrayInputStream(table)));
        assertThatThrownBy(reader::readTable).isInstanceOf(IOException.class)
            .hasMessageContaining("nested tables/arrays");
    }
}
