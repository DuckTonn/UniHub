package com.unihub.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "TRUSTED_PROXY_PATTERN=127[.]0[.]0[.]1")
class GatewayRoutingTest {

    private static final HttpServer BACKEND = startBackend();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("BACKEND_URL", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @Test
    void preservesRawPathQueryAndForwardedClientAddress() throws Exception {
        String path = "/api/v1/workshops?keyword=Spring%20Boot&sort=name&sort=id";
        var response = send(request(path).header("X-Forwarded-For", "192.0.2.10"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("GET " + path);
        assertThat(response.headers().firstValue("X-Observed-Client")).contains("192.0.2.10");
        assertThat(response.headers().firstValue("X-Observed-Host")).contains("127.0.0.1:" + port);
    }

    @Test
    void keepsMultipartBodyAndHeadersForBackendUploads() throws Exception {
        String path = "/api/v1/admin/workshops/42/ai-summary";
        String body = "--example\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n%PDF-example\r\n--example--\r\n";
        var response = send(request(path)
                .header("Authorization", "Bearer example-access-token")
                .header("Idempotency-Key", "example-upload")
                .header("Content-Type", "multipart/form-data; boundary=example")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("POST " + path + body);
        assertThat(response.headers().firstValue("X-Observed-Authorization")).contains("Bearer example-access-token");
        assertThat(response.headers().firstValue("X-Observed-Idempotency-Key")).contains("example-upload");
        assertThat(response.headers().firstValue("X-Observed-Content-Type")).contains("multipart/form-data; boundary=example");
    }

    @Test
    void forwardsRefreshRequestsWithoutAnAccessToken() throws Exception {
        String path = "/api/v1/auth/refresh";
        String body = "{\"refreshToken\":\"example-refresh-token\"}";
        var response = send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Observed-Authorization")).isEmpty();
        assertThat(response.body()).isEqualTo("POST " + path + body);
    }

    @ParameterizedTest
    @CsvSource({"401, Token expired", "403, Forbidden"})
    void passesThroughBackendAuthErrors(int status, String error) throws Exception {
        var response = send(request("/api/v1/admin/stats").header("X-Test-Status", Integer.toString(status)));

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.body()).isEqualTo("{\"error\":\"" + error + "\"}");
    }

    @Test
    void handlesBrowserPreflightWithTheBackendCorsPolicy() throws Exception {
        String origin = "https://client.example";
        var preflight = request("/api/v1/auth/me")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET");
        var allowed = send(preflight.copy().header("Access-Control-Request-Headers", "Authorization"));
        var denied = send(preflight.copy().header("Access-Control-Request-Headers", "X-Unknown"));

        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers")).contains("Authorization");
        assertThat(denied.statusCode()).isEqualTo(403);
    }

    @Test
    void onlyRoutesApiRequestsAndServesItsOwnHealthProbe() throws Exception {
        var health = send(request("/actuator/health"));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");

        assertThat(send(request("/actuator/env")).statusCode()).isEqualTo(404);
        assertThat(send(request("/summarize")).statusCode()).isEqualTo(404);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewayRoutingTest::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getRequestHeaders();
            var responseHeaders = exchange.getResponseHeaders();
            copyIfPresent(exchange, "Host", "X-Observed-Host");
            copyIfPresent(exchange, "X-Forwarded-For", "X-Observed-Client");
            copyIfPresent(exchange, "Authorization", "X-Observed-Authorization");
            copyIfPresent(exchange, "Idempotency-Key", "X-Observed-Idempotency-Key");
            copyIfPresent(exchange, "Content-Type", "X-Observed-Content-Type");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String statusHeader = headers.getFirst("X-Test-Status");
            int status = statusHeader == null ? 200 : Integer.parseInt(statusHeader);
            String body = switch (status) {
                case 401 -> "{\"error\":\"Token expired\"}";
                case 403 -> "{\"error\":\"Forbidden\"}";
                default -> exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + new String(requestBody, StandardCharsets.UTF_8);
            };
            responseHeaders.set("Content-Type", status == 200 ? "text/plain" : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void copyIfPresent(HttpExchange exchange, String requestName, String responseName) {
        String value = exchange.getRequestHeaders().getFirst(requestName);
        if (value != null) {
            exchange.getResponseHeaders().set(responseName, value);
        }
    }
}
