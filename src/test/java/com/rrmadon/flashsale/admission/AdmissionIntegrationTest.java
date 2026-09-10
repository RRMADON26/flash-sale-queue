package com.rrmadon.flashsale.admission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdmissionIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<String> join(String idempotencyKey, String sku) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/queue/join", HttpMethod.POST,
                new HttpEntity<>("{\"sku\":\"" + sku + "\"}", headers), String.class);
    }

    @Test
    void retriedJoinReturnsTheSameTicket_notASecondOne() {
        String sku = "admission-it-" + UUID.randomUUID();
        String key = "retry-" + UUID.randomUUID();

        ResponseEntity<String> first = join(key, sku);
        ResponseEntity<String> retry = join(key, sku);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getBody())
                .as("a retried join must not issue a second ticket for one logical attempt")
                .isEqualTo(first.getBody());
    }

    @Test
    void joinWithoutIdempotencyKeyIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/queue/join", HttpMethod.POST,
                new HttpEntity<>("{\"sku\":\"any\"}", headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
