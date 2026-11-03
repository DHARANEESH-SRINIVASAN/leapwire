INSERT INTO accounts VALUES (1, 'alice', 'Alice Archer', 43);
INSERT INTO accounts VALUES (2, 'bob', 'Bob Brown', 53);

INSERT INTO customers VALUES (19, 1, 'Acme Corp', 'Chennai');
INSERT INTO customers VALUES (20, 1, 'Globex', 'Madurai');
INSERT INTO customers VALUES (29, 2, 'Initech', 'Coimbatore');
INSERT INTO customers VALUES (30, 2, 'Umbrella', 'Salem');

INSERT INTO products VALUES (501, 'Keyboard', 49.00);
INSERT INTO products VALUES (502, 'Mouse', 19.50);
INSERT INTO products VALUES (503, 'Monitor', 229.00);
INSERT INTO products VALUES (504, 'Dock', 129.99);
INSERT INTO products VALUES (505, 'Webcam', 64.25);
INSERT INTO products VALUES (506, 'Headset', 89.00);
INSERT INTO products VALUES (507, 'Cable', 9.75);

INSERT INTO orders (id, user_id, customer_id, status, total) VALUES (42, 1, 19, 'SHIPPED', 88.00);
INSERT INTO orders (id, user_id, customer_id, status, total) VALUES (43, 1, 20, 'PAID', 229.00);
INSERT INTO orders (id, user_id, customer_id, status, total) VALUES (52, 2, 29, 'PACKED', 178.99);
INSERT INTO orders (id, user_id, customer_id, status, total) VALUES (53, 2, 30, 'DELIVERED', 172.75);
INSERT INTO orders (id, user_id, customer_id, status, total) VALUES (54, 2, 19, 'PAID', 19.50);

INSERT INTO order_lines VALUES (1, 42, 501, 1);
INSERT INTO order_lines VALUES (2, 42, 502, 2);
INSERT INTO order_lines VALUES (3, 43, 503, 1);
INSERT INTO order_lines VALUES (4, 52, 501, 1);
INSERT INTO order_lines VALUES (5, 52, 504, 1);
INSERT INTO order_lines VALUES (6, 53, 505, 1);
INSERT INTO order_lines VALUES (7, 53, 506, 1);
INSERT INTO order_lines VALUES (8, 53, 507, 2);
INSERT INTO order_lines VALUES (9, 54, 502, 1);
