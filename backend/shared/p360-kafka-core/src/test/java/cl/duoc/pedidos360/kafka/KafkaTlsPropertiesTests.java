package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class KafkaTlsPropertiesTests {
  @TempDir Path directory;
  @Test void plaintextLocalRemainsCompatible() {
    Properties p = new Properties();
    KafkaTlsProperties.apply(new MockEnvironment(), p);
    assertThat(p).isEmpty();
  }
  @Test void mutualTlsPinsIdentityAndFilesWithoutReadingKeysIntoProperties() throws Exception {
    Path ca=Files.writeString(directory.resolve("ca.pem"), "synthetic");
    Path key=Files.writeString(directory.resolve("client.pem"), "synthetic");
    var env = new MockEnvironment().withProperty("p360.kafka.security-protocol", "SSL")
      .withProperty("p360.kafka.ssl.truststore-location", ca.toString())
      .withProperty("p360.kafka.ssl.keystore-location", key.toString());
    Properties p = new Properties(); KafkaTlsProperties.apply(env,p);
    assertThat(p).containsEntry("security.protocol", "SSL")
      .containsEntry("ssl.endpoint.identification.algorithm", "https")
      .containsEntry("ssl.keystore.type", "PEM")
      .containsEntry("ssl.truststore.location",ca.toString());
    assertThat(p).doesNotContainKeys("ssl.keystore.key", "ssl.keystore.password", "ssl.key.password");
  }
  @Test void refusesSilentDowngrade() {
    assertThatThrownBy(()->KafkaTlsProperties.apply(new MockEnvironment().withProperty("p360.kafka.ssl.truststore-location", "/tmp/ca.pem"),new Properties())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void refusesUnsupportedProtocols() {
    assertThatThrownBy(()->KafkaTlsProperties.apply(new MockEnvironment().withProperty("p360.kafka.security-protocol", "SASL_PLAINTEXT"),new Properties())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void sslRequiresBothExistingAbsoluteFiles() {
    assertThatThrownBy(()->KafkaTlsProperties.apply(new MockEnvironment().withProperty("p360.kafka.security-protocol", "SSL"),new Properties())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->KafkaTlsProperties.apply(new MockEnvironment().withProperty("p360.kafka.security-protocol", "SSL").withProperty("p360.kafka.ssl.truststore-location", "relative.pem"),new Properties())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void refusesDirectoryAsTruststore() {
    assertThatThrownBy(()->KafkaTlsProperties.apply(new MockEnvironment().withProperty("p360.kafka.security-protocol", "SSL").withProperty("p360.kafka.ssl.truststore-location", directory.toString()),new Properties())).isInstanceOf(IllegalArgumentException.class);
  }
}
