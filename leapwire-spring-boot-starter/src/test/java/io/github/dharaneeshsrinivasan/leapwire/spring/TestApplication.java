package io.github.dharaneeshsrinivasan.leapwire.spring;

import io.github.dharaneeshsrinivasan.leapwire.LeapwireSkip;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class TestApplication {

    public record Order(long id, long customerId, long auditId, long hiddenId, long slowA, long slowB, String status) {
    }

    public record Customer(long id, String name, String seenBy, String language, String speculative,
                           String leapwireAccept) {
    }

    public record Line(long productId, int quantity) {
    }

    public record Product(String id, String name) {
    }

    public record Session(long userId) {
    }

    public record User(long id, long latestOrderId) {
    }

    public record Variant(long id, long customerId) {
    }

    public record Touch(String name, long id) {
    }

    static final class Calls {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        int touch(String name) {
            return counts.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
        }

        int count(String name) {
            AtomicInteger count = counts.get(name);
            return count == null ? 0 : count.get();
        }
    }

    static final class Delays {

        private final Map<String, Long> millis = new ConcurrentHashMap<>();

        void set(String name, long value) {
            millis.put(name, value);
        }

        void clear() {
            millis.clear();
        }

        void sleep(String name) {
            long value = millis.getOrDefault(name, 0L);
            if (value > 0) {
                try {
                    Thread.sleep(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Bean
    Calls calls() {
        return new Calls();
    }

    @Bean
    Delays delays() {
        return new Delays();
    }

    @Bean
    FilterRegistrationBean<Filter> testPrincipalFilter() {
        Filter filter = (request, response, chain) -> {
            String authorization = ((HttpServletRequest) request).getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Test ")) {
                chain.doFilter(request, response);
                return;
            }
            String name = authorization.substring("Test ".length());
            chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                @Override
                public Principal getUserPrincipal() {
                    return () -> name;
                }
            }, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @RestController
    static class ShopController {

        private final Calls calls;
        private final Delays delays;

        ShopController(Calls calls, Delays delays) {
            this.calls = calls;
            this.delays = delays;
        }

        @GetMapping("/api/orders/{id}")
        Order order(@PathVariable long id) {
            if (id < 0 || id > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            calls.touch("order");
            return new Order(id, id + 100, id + 200, id + 300, id + 400, id + 500, "PAID");
        }

        @GetMapping("/api/customers/{id}")
        Customer customer(@PathVariable long id, Principal principal,
                          @RequestHeader(value = "Accept-Language", required = false) String language,
                          @RequestHeader(value = Protocol.SPECULATIVE, required = false) String speculative,
                          @RequestHeader(value = Protocol.ACCEPT, required = false) String leapwireAccept) {
            calls.touch("customer");
            return new Customer(id, "Customer " + id, principal == null ? null : principal.getName(), language,
                    speculative, leapwireAccept);
        }

        @GetMapping("/api/orders/{id}/lines")
        List<Line> lines(@PathVariable long id) {
            if (id < 0 || id > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return List.of(new Line(id * 100 + 1, 1), new Line(id * 100 + 2, 2));
        }

        @GetMapping("/api/products")
        List<Product> products(@RequestParam List<String> ids) {
            return ids.stream().map(id -> new Product(id, "Product " + id)).toList();
        }

        @GetMapping("/api/session")
        Session session() {
            return new Session(7);
        }

        @GetMapping("/api/users/{id}")
        User user(@PathVariable long id) {
            if (id < 0 || id > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return new User(id, id * 6);
        }

        @GetMapping("/api/slow-a/{id}")
        Touch slowA(@PathVariable long id) {
            delays.sleep("a");
            calls.touch("slow-a");
            return new Touch("a", id);
        }

        @GetMapping("/api/slow-b/{id}")
        Touch slowB(@PathVariable long id) {
            delays.sleep("b");
            calls.touch("slow-b");
            return new Touch("b", id);
        }

        @LeapwireSkip
        @GetMapping("/api/audit/{id}")
        Touch audit(@PathVariable long id) {
            calls.touch("audit");
            return new Touch("audit", id);
        }

        @GetMapping("/api/variant/{id}")
        ResponseEntity<?> variant(@PathVariable long id, @RequestParam(defaultValue = "json") String as) {
            if (id < 0 || id > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            if (as.equals("text")) {
                return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("variant " + id);
            }
            if (as.equals("missing")) {
                return ResponseEntity.status(404).body(Map.of("error", "missing", "id", id));
            }
            return ResponseEntity.ok(new Variant(id, id + 100));
        }

        @GetMapping("/api/tagged/{id}")
        ResponseEntity<String> tagged(@PathVariable long id) {
            if (id < 0 || id > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            String pretty = "{\n  \"id\" : " + id + ",\n  \"customerId\" : " + (id + 100)
                    + ",\n  \"price\" : 1.50\n}\n";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .eTag("\"v" + id + "\"")
                    .lastModified(1_700_000_000_000L)
                    .cacheControl(CacheControl.noCache())
                    .body(pretty);
        }

        @GetMapping("/api/text/{id}")
        ResponseEntity<String> text(@PathVariable long id) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("text " + id);
        }
    }

    @LeapwireSkip
    @RestController
    static class HiddenController {

        private final Calls calls;

        HiddenController(Calls calls) {
            this.calls = calls;
        }

        @GetMapping("/api/hidden/{id}")
        Touch hidden(@PathVariable long id) {
            calls.touch("hidden");
            return new Touch("hidden", id);
        }
    }
}
