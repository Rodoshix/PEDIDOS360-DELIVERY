import java.net.URI;
import javax.net.ssl.HttpsURLConnection;

// Sonda de prueba: conserva la validación de certificado y hostname de Java.
class TlsProbe {
    public static void main(String[] args) throws Exception {
        var connection = (HttpsURLConnection) URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status != 200) throw new IllegalStateException("HTTP " + status);
        System.out.println("200");
    }
}
