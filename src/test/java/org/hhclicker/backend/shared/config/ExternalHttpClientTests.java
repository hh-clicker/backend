package org.hhclicker.backend.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExternalHttpClientTests {
    @Test
    void anAcceptedConnectionWithoutAResponseHasABoundedReadWait() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(3000);
            var receiver = executor.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return null;
            });
            try {
                var client = ExternalHttpClient.builder(Duration.ofMillis(200)).build();
                var request = executor.submit(() -> assertThrows(RestClientException.class,
                        () -> client.get().uri("http://127.0.0.1:" + server.getLocalPort() + "/blocked")
                                .retrieve().toBodilessEntity()));
                assertTrue(accepted.await(3, TimeUnit.SECONDS));
                assertNotNull(request.get(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                server.close();
            }
            receiver.get(3, TimeUnit.SECONDS);
        }
    }
}
