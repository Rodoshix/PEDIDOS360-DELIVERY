package cl.duoc.pedidos360.messaging;

import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CatalogResponseSchemaTests {
    final ResponseSchema schema = new ResponseSchema();
    String body(String op, String payload, String error, boolean success, int status) {
        return "{\"operacion\":\""+op+"\",\"correlationId\":\""+UUID.randomUUID()+"\",\"messageId\":\""+UUID.randomUUID()
                +"\",\"success\":"+success+",\"status\":"+status+",\"payload\":"+payload+",\"error\":"+error+",\"timestamp\":\"2026-10-09T00:00:00Z\"}";
    }
    boolean valid(String body) { return schema.leer(body.getBytes(StandardCharsets.UTF_8),262144).isPresent(); }
    @ParameterizedTest @ValueSource(strings={"usuario.consultar-actual.v1","pago.consultar.v1"})
    void objectOnly(String op) {
        assertThat(valid(body(op,"{}","null",true,200))).isTrue();
        assertThat(valid(body(op,"[]","null",true,200))).isFalse();
        assertThat(valid(body(op,"[{}]","null",true,200))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"restaurante.listar.v1","producto.listar-disponibles.v1"})
    void arraysIncludingEmptyOnly(String op) {
        assertThat(valid(body(op,"[{}]","null",true,200))).isTrue();
        assertThat(valid(body(op,"[]","null",true,200))).isTrue();
        assertThat(valid(body(op,"{}","null",true,200))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"usuario.consultar-actual.v1","pago.consultar.v1","restaurante.listar.v1","producto.listar-disponibles.v1"})
    void errorContractPreserved(String op) {
        for(int status : new int[]{403,404}) {
            String error="{\"code\":\"BUSINESS\",\"title\":\"Error\",\"detail\":\"Denied\",\"status\":"+status+"}";
            assertThat(valid(body(op,"null",error,false,status))).isTrue();
            assertThat(valid(body(op,"[]",error,false,status))).isFalse();
            assertThat(valid(body(op,"{}",error,false,status))).isFalse();
        }
    }
    @ParameterizedTest @ValueSource(strings={"unknown.v1","restaurante.listar.v2","Restaurante.Listar.v1"})
    void unknownOperationRejected(String op) {
        assertThat(valid(body(op,"[]","null",true,200))).isFalse();
        assertThat(valid(body(op,"{}","null",true,200))).isFalse();
        assertThat(valid(body(op,"null","{\"code\":\"x\",\"title\":\"x\",\"status\":404}",false,404))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"null","1","true","\"text\""})
    void scalarAndNullSuccessRejected(String payload) {
        for(String op : new String[]{"usuario.consultar-actual.v1","restaurante.listar.v1"})
            assertThat(valid(body(op,payload,"null",true,200))).isFalse();
    }
    @Test void malformedStructureDuplicateFieldsAndTypesRejected() {
        String b=body("restaurante.listar.v1","[]","null",true,200);
        assertThat(valid(b.replace("\"payload\":[]","\"payload\":[],\"payload\":[]"))).isFalse();
        assertThat(valid(b.replace("\"payload\":[]","\"payload\":[{\"id\":1,\"id\":2}]"))).isFalse();
        assertThat(valid(b.replace("\"error\":null,",""))).isFalse();
        assertThat(valid(b.replace("\"error\":null","\"extra\":1,\"error\":null"))).isFalse();
        assertThat(valid(b+"{}" )).isFalse();
        assertThat(valid(b.replace("\"status\":200","\"status\":4294967496"))).isFalse();
        assertThat(valid(b.replace("\"status\":200","\"status\":\"200\""))).isFalse();
        assertThat(valid(b.replace("\"status\":200","\"status\":404"))).isFalse();
        assertThat(valid(b.replace("2026-10-09T00:00:00Z","bad"))).isFalse();
        assertThat(valid(b.replaceFirst("\"messageId\":\"[^\"]+\"","\"messageId\":\"bad\""))).isFalse();
        assertThat(valid(b.replace("\"error\":null","\"error\":{}"))).isFalse();
    }
    @Test void bindingRunsBeforePayloadShapeAndDoesNotSwallowDeadlineFailure() {
        var b=body("restaurante.listar.v1","{}","null",true,200).getBytes(StandardCharsets.UTF_8);
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThat(schema.leer(b,262144,response->{called.set(true);return true;})).isEmpty();
        assertThat(called).isTrue();
        assertThatThrownBy(()->schema.leer(b,262144,response->{throw new cl.duoc.pedidos360.messaging.relay.QueryTimeoutException("expired");}))
                .isInstanceOf(cl.duoc.pedidos360.messaging.relay.QueryTimeoutException.class);
    }
}
