package com.lilac.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {

    @Test
    void builderKeepsDefaults() {
        ClientConfig config = ClientConfig.builder().apiKey("sk-test").build();
        assertEquals("http://localhost:9090/api", config.getBaseUrl());
        assertEquals(10000, config.getConnectTimeout());
        assertEquals(30000, config.getReadTimeout());
        assertEquals(3, config.getMaxRetries());
    }

    @Test
    void validateRejectsBlankApiKey() {
        ClientConfig config = ClientConfig.builder().apiKey("  ").build();
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}