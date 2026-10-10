package io.loomvault.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.loomvault.server.config.LoomVaultProperties;

/**
 * LoomVault application entry point.
 *
 * <p>The server is a thin shell around {@code loomvault-core}: it wires ports to adapters, exposes
 * a REST/SSE surface, and serves the bundled control-room UI. Every scheduling decision lives in
 * the core, which is why the same engine can be embedded or unit-tested without Spring.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(LoomVaultProperties.class)
public class LoomVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomVaultApplication.class, args);
    }
}
