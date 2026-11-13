package cl.duoc.pedidos360.kafka;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.security.ssl.DefaultSslEngineFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Real loopback TLS with Kafka's SSL factory, NOT a real Kafka broker. */
class KafkaTlsTransportTests {
  @TempDir Path dir;
  Path ca, client;
  DefaultSslEngineFactory factory;
  @BeforeEach void material() throws Exception {
    String openssl=System.getenv().getOrDefault("P360_TEST_OPENSSL", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "C:/Program Files/Git/usr/bin/openssl.exe" : "openssl");
    ca=dir.resolve("cert.pem"); Path key=dir.resolve("key.pem");
    var process=new ProcessBuilder(openssl,"req","-x509","-newkey","rsa:2048","-nodes","-days","2","-subj","/CN=localhost","-addext","subjectAltName=DNS:localhost","-addext","extendedKeyUsage=serverAuth,clientAuth","-keyout",key.toString(),"-out",ca.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
    client=Files.writeString(dir.resolve("client.pem"),Files.readString(key)+Files.readString(ca));
    factory=makeFactory(true);
  }
  @AfterEach void close() { if(factory!=null)factory.close(); }
  DefaultSslEngineFactory makeFactory(boolean certificate) {
    var env=new MockEnvironment().withProperty("p360.kafka.security-protocol","SSL")
      .withProperty("p360.kafka.ssl.truststore-location",ca.toString())
      .withProperty("p360.kafka.ssl.keystore-location",client.toString());
    Properties p=KafkaRuntime.properties(env);
    if(!certificate){p.remove("ssl.keystore.location");p.remove("ssl.keystore.type");}
    var result=new DefaultSslEngineFactory(); result.configure(new AdminClientConfig(p).values()); return result;
  }
  void handshake(String host, DefaultSslEngineFactory connecting) throws Exception {
    try(var server=(SSLServerSocket)factory.sslContext().getServerSocketFactory().createServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
      server.setNeedClientAuth(true); server.setSoTimeout(3000);
      var executor=Executors.newSingleThreadExecutor();
      Future<Boolean> peer=executor.submit(()->{try(var socket=(SSLSocket)server.accept()){socket.setSoTimeout(3000);socket.startHandshake();return socket.getSession().getPeerCertificates().length>0;}catch(Exception e){return false;}});
      try(var socket=(SSLSocket)connecting.sslContext().getSocketFactory().createSocket(new java.net.Socket("127.0.0.1",server.getLocalPort()),host,server.getLocalPort(),true)) {
        socket.setSoTimeout(3000); var ssl=socket.getSSLParameters();ssl.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(ssl);socket.startHandshake();
        assertThat(peer.get(5,TimeUnit.SECONDS)).isTrue();
      } finally {executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
  }
  @Test void realMutualTlsAndKafkaHostnameVerification() throws Exception {
    assertThat(factory.createClientSslEngine("localhost",9093,"https").getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("https");
    handshake("localhost",factory);
  }
  @Test void wrongHostnameFailsAndSubsequentConnectionRecovers() throws Exception {
    assertThatThrownBy(()->handshake("127.0.0.1",factory)).isInstanceOf(SSLHandshakeException.class);
    handshake("localhost",factory);
  }
  @Test void serverRejectsClientWithoutCertificate() throws Exception {
    try(var uncredentialed=new AutoCloseableFactory(makeFactory(false))) {
      assertThatThrownBy(()->handshake("localhost",uncredentialed.value)).isInstanceOfAny(SSLException.class,AssertionError.class);
    }
  }
  record AutoCloseableFactory(DefaultSslEngineFactory value) implements AutoCloseable {public void close(){value.close();}}
}
