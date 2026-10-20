CREATE TABLE customers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_number VARCHAR(20) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_customer_number UNIQUE (customer_number),
    CONSTRAINT uk_customers_email UNIQUE (email),
    CONSTRAINT chk_customers_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED'))
);