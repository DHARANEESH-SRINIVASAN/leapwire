package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

final class Speculator implements ApplicationListener<WebServerInitializedEvent>, AutoCloseable {

    static final List<String> COPIED = List.of("Authorization", "Cookie", "Accept-Language", Protocol.PAGE,
            Protocol.ROUTE);

    private static final Log logger = LogFactory.getLog(Speculator.class);

    private final Environment environment;
    private final int maxBodyBytes;
    private final ExecutorService executor = executor();
    private final HttpClient client;
    private volatile String origin;

    Speculator(Environment environment, LeapwireProperties properties) {
        this.environment = environment;
        this.maxBodyBytes = properties.maxBodyBytes();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.deadline())
                .build();
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() != null) {
            return;
        }
        if (Ssl.isEnabled(Binder.get(environment).bind("server.ssl", Ssl.class).orElse(null))) {
            origin = null;
            logger.warn("Leapwire is off: the server uses TLS (server.ssl), and Leapwire makes its speculative "
                    + "requests as plain HTTP calls to itself. Terminate TLS in a proxy in front of this "
                    + "application to use Leapwire.");
            return;
        }
        origin = "http://" + host() + ":" + event.getWebServer().getPort();
    }

    boolean ready() {
        return origin != null;
    }

    static List<Header> headers(HttpServletRequest request) {
        List<Header> headers = new ArrayList<>();
        for (String name : COPIED) {
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                headers.add(new Header(name, values.nextElement()));
            }
        }
        return headers;
    }

    Future<?> submit(String url, List<Header> headers, long deadline, BlockingQueue<Part> done) {
        String base = origin;
        try {
            return executor.submit(() -> {
                Part part;
                try {
                    part = base == null ? Part.failed(url) : fetch(base, url, headers, deadline);
                } catch (RuntimeException e) {
                    part = Part.failed(url);
                }
                done.add(part);
            });
        } catch (RejectedExecutionException e) {
            done.add(Part.failed(url));
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Part fetch(String base, String url, List<Header> headers, long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return Part.failed(url);
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + url))
                    .timeout(Duration.ofNanos(left))
                    .GET();
            for (Header header : headers) {
                request.header(header.name(), header.value());
            }
            request.header("Accept", "application/json").header(Protocol.SPECULATIVE, "1");
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(maxBodyBytes + 1);
            }
            if (response.statusCode() != 200) {
                return new Part(url, response.statusCode(), Map.of(), null);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            if (body.length > maxBodyBytes || !Json.isJson(contentType)) {
                return Part.failed(url);
            }
            JsonNode tree = Json.parse(body, contentType);
            if (tree == null) {
                return Part.failed(url);
            }
            return new Part(url, 200, Json.headers(name -> response.headers().firstValue(name).orElse(null)), tree);
        } catch (IOException | IllegalArgumentException e) {
            return Part.failed(url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Part.failed(url);
        }
    }

    private String host() {
        String address = environment.getProperty("server.address");
        if (address == null || address.isBlank()) {
            return "127.0.0.1";
        }
        try {
            InetAddress inet = InetAddress.getByName(address.trim());
            if (inet.isAnyLocalAddress()) {
                return "127.0.0.1";
            }
            return inet instanceof Inet6Address ? "[" + inet.getHostAddress() + "]" : inet.getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    private static ExecutorService executor() {
        try {
            return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (ReflectiveOperationException e) {
            int threads = Math.max(16, 4 * Runtime.getRuntime().availableProcessors());
            AtomicInteger count = new AtomicInteger();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1024), runnable -> {
                        Thread thread = new Thread(runnable, "leapwire-" + count.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            return pool;
        }
    }

    record Header(String name, String value) {
    }

    record Part(String url, int status, Map<String, String> headers, JsonNode body) {

        static Part failed(String url) {
            return new Part(url, 0, Map.of(), null);
        }
    }
}
