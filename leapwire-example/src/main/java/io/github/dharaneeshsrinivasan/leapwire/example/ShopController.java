package io.github.dharaneeshsrinivasan.leapwire.example;

import io.github.dharaneeshsrinivasan.leapwire.LeapwireSkip;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class ShopController {

    record Session(long userId, String username) {
    }

    record Account(long id, String username, String name, long latestOrderId) {
    }

    record Order(long id, long userId, long customerId, String status, BigDecimal total) {
    }

    record Line(long orderId, long productId, int quantity) {
    }

    record Customer(long id, String name, String city) {
    }

    record Product(long id, String name, BigDecimal price) {
    }

    record Views(long orderId, int views) {
    }

    private record CustomerRow(long id, long ownerId, String name, String city) {
    }

    private final JdbcClient jdbc;
    private final ExampleProperties properties;

    ShopController(JdbcClient jdbc, ExampleProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @GetMapping("/session")
    Session session(Principal principal) {
        pause();
        Account me = me(principal);
        return new Session(me.id(), me.username());
    }

    @GetMapping("/users/{id}")
    Account user(@PathVariable long id, Principal principal) {
        pause();
        Account me = me(principal);
        if (me.id() != id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return me;
    }

    @GetMapping("/orders/{id}")
    Order order(@PathVariable long id, Principal principal) {
        pause();
        return owned(id, principal);
    }

    @GetMapping("/orders/{id}/lines")
    List<Line> lines(@PathVariable long id, Principal principal) {
        pause();
        owned(id, principal);
        return jdbc.sql("SELECT order_id, product_id, quantity FROM order_lines WHERE order_id = ? ORDER BY id")
                .param(id)
                .query(Line.class)
                .list();
    }

    @GetMapping("/customers/{id}")
    Customer customer(@PathVariable long id, Principal principal) {
        pause();
        Account me = me(principal);
        CustomerRow row = jdbc.sql("SELECT id, owner_id, name, city FROM customers WHERE id = ?")
                .param(id)
                .query(CustomerRow.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (row.ownerId() != me.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return new Customer(row.id(), row.name(), row.city());
    }

    @GetMapping("/products")
    List<Product> products(@RequestParam List<Long> ids) {
        pause();
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name, price FROM products WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query(Product.class)
                .list();
    }

    @LeapwireSkip
    @GetMapping("/orders/{id}/views")
    Views views(@PathVariable long id, Principal principal) {
        pause();
        owned(id, principal);
        jdbc.sql("UPDATE orders SET views = views + 1 WHERE id = ?").param(id).update();
        return new Views(id, jdbc.sql("SELECT views FROM orders WHERE id = ?").param(id).query(Integer.class).single());
    }

    private Account me(Principal principal) {
        return jdbc.sql("SELECT id, username, name, latest_order_id FROM accounts WHERE username = ?")
                .param(principal.getName())
                .query(Account.class)
                .single();
    }

    private Order owned(long id, Principal principal) {
        Order order = jdbc.sql("SELECT id, user_id, customer_id, status, total FROM orders WHERE id = ?")
                .param(id)
                .query(Order.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (order.userId() != me(principal).id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return order;
    }

    private void pause() {
        long millis = properties.delay().toMillis();
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
