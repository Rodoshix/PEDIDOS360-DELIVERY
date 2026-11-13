package cl.duoc.pedidos360.kafkaadmin;

import java.nio.file.*;
import java.util.Properties;
import org.springframework.core.env.Environment;

/** Explicit PEM mutual TLS; preserves plaintext only for existing local fixtures. */
public final class KafkaTlsProperties {
  private KafkaTlsProperties() {}

  public static void apply(Environment env, Properties properties) {
    String protocol = env.getProperty("p360.kafka.security-protocol", "PLAINTEXT");
    String trust = env.getProperty("p360.kafka.ssl.truststore-location");
    String key = env.getProperty("p360.kafka.ssl.keystore-location");
    if ("PLAINTEXT".equals(protocol)) {
      if (trust != null || key != null)
        throw new IllegalArgumentException("TLS files require explicit SSL protocol");
      return;
    }
    if (!"SSL".equals(protocol)) throw new IllegalArgumentException("Unsupported Kafka security protocol");
    properties.setProperty("security.protocol", "SSL");
    properties.setProperty("ssl.truststore.type", "PEM");
    properties.setProperty("ssl.keystore.type", "PEM");
    properties.setProperty("ssl.truststore.location", file(trust));
    properties.setProperty("ssl.keystore.location", file(key));
    // Deliberately not configurable: no hostname-verification bypass or TLS downgrade.
    properties.setProperty("ssl.endpoint.identification.algorithm", "https");
    properties.setProperty("ssl.enabled.protocols", "TLSv1.2,TLSv1.3");
  }

  private static String file(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing Kafka TLS file");
    Path path;
    try { path = Path.of(value); }
    catch (InvalidPathException e) { throw new IllegalArgumentException("Invalid Kafka TLS file"); }
    if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
      throw new IllegalArgumentException("Kafka TLS file must be an absolute readable regular file");
    return path.toString();
  }
}
