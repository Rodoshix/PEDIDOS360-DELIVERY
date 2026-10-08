package cl.duoc.pedidos360.messaging.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonicalización de este esquema plano: claves ordenadas, JSON compacto y timestamps de milisegundos. */
public final class IdentityProofCodec {
    private IdentityProofCodec() {}
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    static final Set<String> FIELDS = Set.of("v", "iss", "tenantId", "entraObjectId", "usuarioId", "activo",
            "perfilVerificadoEn", "emitidoEn", "expiraEn", "deadlineOriginal", "aud", "operacion", "usuariosRequestId", "jti");
    public static Instant millis(Instant value) { return value.truncatedTo(ChronoUnit.MILLIS); }
    public static String timestamp(Instant value) { return TIME.format(millis(value)); }
    public static String header(UUID kid) {
        return encode(JSON.writeValueAsBytes(new TreeMap<>(Map.of("alg", "ES256", "typ", IdentityProof.TYPE, "kid", kid.toString()))));
    }
    public static String payload(IdentityProof p) {
        var m = new TreeMap<String, Object>();
        m.put("v", 1); m.put("iss", IdentityProof.ISSUER); m.put("aud", IdentityProof.AUDIENCE);
        m.put("operacion", IdentityProof.OPERATION); m.put("activo", true);
        m.put("tenantId", p.tenantId().toString()); m.put("entraObjectId", p.entraObjectId().toString());
        m.put("usuarioId", p.usuarioId()); m.put("usuariosRequestId", p.usuariosRequestId().toString()); m.put("jti", p.jti().toString());
        m.put("perfilVerificadoEn", timestamp(p.perfilVerificadoEn())); m.put("emitidoEn", timestamp(p.emitidoEn()));
        m.put("expiraEn", timestamp(p.expiraEn())); m.put("deadlineOriginal", timestamp(p.deadlineOriginal()));
        return encode(JSON.writeValueAsBytes(m));
    }
    public static String encode(byte[] raw) { return Base64.getUrlEncoder().withoutPadding().encodeToString(raw); }
    static byte[] decode(String value, int limit) {
        try {
            if (value.length() > (limit * 4 + 2) / 3 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if (raw.length > limit || !encode(raw).equals(value)) throw new IllegalArgumentException();
            return raw;
        } catch (RuntimeException invalid) { throw new IdentityProofException(IdentityProofException.Reason.FORMATO); }
    }
    static JsonNode object(byte[] raw) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            if (!java.util.Arrays.equals(raw, JSON.writeValueAsBytes(sorted))) throw new IllegalArgumentException();
            return node;
        } catch (Exception invalid) { throw new IdentityProofException(IdentityProofException.Reason.FORMATO); }
    }
    static Set<String> fields(JsonNode node) {
        var fields = new java.util.HashSet<String>();
        node.properties().forEach(e -> fields.add(e.getKey())); return fields;
    }
    static String text(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || !v.isString()) throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        return v.stringValue();
    }
    static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException(); return id;
        } catch (RuntimeException invalid) { throw new IdentityProofException(IdentityProofException.Reason.FORMATO); }
    }
    static Instant instant(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")) throw new IllegalArgumentException();
            Instant time = Instant.parse(value);
            if (!timestamp(time).equals(value)) throw new IllegalArgumentException(); return time;
        } catch (RuntimeException invalid) { throw new IdentityProofException(IdentityProofException.Reason.FORMATO); }
    }
    static IdentityProof read(JsonNode n) {
        if (!fields(n).equals(FIELDS) || !n.path("v").isIntegralNumber() || !n.path("v").canConvertToInt() || n.path("v").intValue() != 1
                || !n.path("activo").isBoolean() || !n.path("activo").booleanValue()
                || !IdentityProof.ISSUER.equals(text(n,"iss")) || !IdentityProof.AUDIENCE.equals(text(n,"aud"))
                || !IdentityProof.OPERATION.equals(text(n,"operacion")) || !n.path("usuarioId").isIntegralNumber()
                || !n.path("usuarioId").canConvertToLong()) throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
        return new IdentityProof(uuid(text(n,"tenantId")), uuid(text(n,"entraObjectId")), n.path("usuarioId").longValue(),
                instant(text(n,"perfilVerificadoEn")), instant(text(n,"emitidoEn")), instant(text(n,"expiraEn")),
                instant(text(n,"deadlineOriginal")), uuid(text(n,"usuariosRequestId")), uuid(text(n,"jti")));
    }
}
