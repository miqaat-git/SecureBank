package com.securebank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 0 check 5. These tests send real HTTP requests to the embedded Tomcat started by
 * Spring Boot, so the JSP engine actually runs. MockMvc cannot prove that: it never executes JSP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "nodb"})
class JspRenderingHttpTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void proofPageIsRenderedByTheJspEngine() throws Exception {
        HttpResponse<String> response = send("GET", "/phase0/proof");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        String body = response.body();
        // EL was evaluated.
        assertThat(body).contains("EL arithmetic: 3");
        // JSTL c:forEach generated one list item per model value, and c:out escaped the last one.
        assertThat(body).contains("<li>alpha</li>", "<li>beta</li>", "<li>gamma</li>");
        assertThat(body).contains("<li>a&amp;b &lt;i&gt;tag&lt;/i&gt;</li>");
        // The runtime versions come from the running container.
        assertThat(body).contains("<li>Servlet container: Apache Tomcat/");
        assertThat(body).contains("Educational banking simulator. Fictional customers and simulated funds. Not a real bank.");
        // Nothing was left unprocessed.
        assertThat(body).doesNotContain("${").doesNotContain("<c:").doesNotContain("<%");
    }

    @Test
    void jspFilesUnderWebInfAreNotServedDirectly() throws Exception {
        for (String path : new String[] {"/WEB-INF/views/phase0/proof.jsp", "/phase0/proof.jsp"}) {
            HttpResponse<String> response = send("GET", path);
            assertThat(response.statusCode()).as(path).isNotEqualTo(200);
            assertThat(response.body()).as(path).doesNotContain("<li>alpha</li>");
        }
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        assertThat(send("POST", "/phase0/proof").statusCode()).isEqualTo(403);
    }

    @Test
    void everyOtherRequestIsDenied() throws Exception {
        assertThat(send("GET", "/").statusCode()).isEqualTo(403);
        assertThat(send("GET", "/anything-else").statusCode()).isEqualTo(403);
    }

    @Test
    void defaultSecurityHeadersAreSent() throws Exception {
        HttpResponse<String> response = send("GET", "/phase0/proof");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }
}
