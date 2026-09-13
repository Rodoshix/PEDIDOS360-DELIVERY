import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
/** Healthcheck HTTPS con confianza y hostname verificados por la JVM. */
public class Health {
    public static void main(String[] args) {
        try {
            var port = System.getenv("SERVER_PORT");
            var connection = (HttpsURLConnection) URI.create("https://localhost:" + port + "/actuator/health").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception ignored) { System.exit(1); }
    }
}
