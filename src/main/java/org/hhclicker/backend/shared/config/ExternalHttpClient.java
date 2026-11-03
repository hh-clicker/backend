package org.hhclicker.backend.shared.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Bounded transport waits; generation itself is polled as an asynchronous job. */
public final class ExternalHttpClient {
    private ExternalHttpClient() { }

    public static RestClient.Builder generationAgent() {
        return builder(Duration.ofSeconds(15));
    }

    public static RestClient.Builder telegram() {
        // getUpdates uses a 20-second long poll, so its transport timeout must be longer.
        return builder(Duration.ofSeconds(30));
    }

    static RestClient.Builder builder(Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
