package com.alphaledger.valuation.marketdata;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * The only place market data reaches the network. Every request first passes the guard: it throws unless the mode is
 * LIVE and {@code network-enabled} is true, so OFFLINE runs (demo, tests, CI) can never make an external call.
 */
@Component
public class MarketDataHttp {

    static final String USER_AGENT = "Mozilla/5.0 (AlphaLedger portfolio project; personal use)";

    private final MarketDataProperties properties;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MarketDataHttp(MarketDataProperties properties) {
        this.properties = properties;
    }

    /** Thrown when a fetch is attempted while the network guard is off. */
    public static class NetworkDisabledException extends IllegalStateException {
        NetworkDisabledException(String url) {
            super("Network access is disabled (mode OFFLINE or network-enabled=false); refused " + url);
        }
    }

    /** Thrown for a non-200 answer, e.g. 404 for a holiday or a file not yet published. */
    public static class NotAvailableException extends IllegalStateException {
        private final int status;

        NotAvailableException(String url, int status) {
            super("HTTP " + status + " from " + url);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public void requireNetwork(String url) {
        if (!properties.networkAllowed()) {
            throw new NetworkDisabledException(url);
        }
    }

    public byte[] get(String url) {
        requireNetwork(url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new NotAvailableException(url, response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException("Download failed: " + url + " (" + ex.getMessage() + ")", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading " + url, ex);
        }
    }
}
