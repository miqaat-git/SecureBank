package com.securebank.gateway.config;

/** Test-only bridge to the package-private URL validator in {@link RouteConfig}. */
public final class RouteConfigAccess {

    private RouteConfigAccess() {
    }

    public static String target(String envName, String value) {
        return RouteConfig.target(envName, value);
    }
}
