package com.securebank.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securebank.gateway.config.RouteConfigAccess;
import org.junit.jupiter.api.Test;

class RouteConfigTest {

    @Test
    void acceptsPlainBaseUrls() {
        assertThat(RouteConfigAccess.target("X", "http://localhost:8081")).isEqualTo("http://localhost:8081");
        assertThat(RouteConfigAccess.target("X", " https://accounts.internal:8443/ ")).isEqualTo("https://accounts.internal:8443");
    }

    @Test
    void rejectsUrlsWithPathsQueriesOrWrongSchemes() {
        for (String bad : new String[]{"", "   ", "localhost:8081", "ftp://host", "http://host/api", "http://host?x=1", "not a url"}) {
            assertThatThrownBy(() -> RouteConfigAccess.target("ACCOUNT_SERVICE_BASE_URL", bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ACCOUNT_SERVICE_BASE_URL");
        }
    }
}
