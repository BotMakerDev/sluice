package com.botmaker.sluice.target;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** The two questions a target asks a server, behind an interface so a test answers instead of the network. */
interface Http {

    /** The status a {@code HEAD} of {@code url} answers; empty when the server did not answer. */
    Optional<Integer> head(String url);

    /** The body of {@code url}; empty unless the server answered 200. */
    Optional<String> get(String url);

    /** The network, each request given twenty seconds. */
    static Http real() {
        Duration timeout = Duration.ofSeconds(20);
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new Http() {
            @Override
            public Optional<Integer> head(String url) {
                return send(HttpRequest.newBuilder().method("HEAD", HttpRequest.BodyPublishers.noBody()), url,
                        HttpResponse.BodyHandlers.discarding()).map(HttpResponse::statusCode);
            }

            @Override
            public Optional<String> get(String url) {
                return send(HttpRequest.newBuilder().GET(), url, HttpResponse.BodyHandlers.ofString())
                        .filter(response -> response.statusCode() == 200).map(HttpResponse::body);
            }

            private <T> Optional<HttpResponse<T>> send(HttpRequest.Builder request, String url,
                                                       HttpResponse.BodyHandler<T> body) {
                try {
                    return Optional.of(http.send(request.uri(URI.create(url)).timeout(timeout).build(), body));
                } catch (IOException | IllegalArgumentException e) {
                    return Optional.empty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        };
    }
}
