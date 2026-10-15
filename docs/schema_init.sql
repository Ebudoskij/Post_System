CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE branches (
    id SERIAL PRIMARY KEY,
    address VARCHAR(255),
    city VARCHAR(100)
);

CREATE TABLE positions (
    id SERIAL PRIMARY KEY,
    title VARCHAR(100)
);

CREATE TABLE parcel_statuses (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50)
);

CREATE TABLE tariffs (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    price_per_kg DECIMAL(10,2)
);

CREATE TABLE payment_methods (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50)
);

CREATE TABLE vehicles (
    id SERIAL PRIMARY KEY,
    license_plate VARCHAR(20),
    capacity_kg DECIMAL(10,2)
);

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    role_id INT REFERENCES roles(id),
    full_name VARCHAR(150),
    phone VARCHAR(20),
    is_deleted BOOLEAN DEFAULT FALSE,
    last_modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE employees (
    id SERIAL PRIMARY KEY,
    branch_id INT REFERENCES branches(id),
    position_id INT REFERENCES positions(id),
    full_name VARCHAR(150),
    is_deleted BOOLEAN DEFAULT FALSE,
    last_modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE parcels (
    id SERIAL PRIMARY KEY,
    tracking_number VARCHAR(50) UNIQUE,
    sender_id INT REFERENCES users(id),
    receiver_id INT REFERENCES users(id),
    weight DECIMAL(10,2),
    status_id INT REFERENCES parcel_statuses(id),
    is_deleted BOOLEAN DEFAULT FALSE,
    last_modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tracking_history (
    id SERIAL PRIMARY KEY,
    parcel_id INT REFERENCES parcels(id),
    status_id INT REFERENCES parcel_statuses(id),
    branch_id INT REFERENCES branches(id),
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payments (
    id SERIAL PRIMARY KEY,
    parcel_id INT REFERENCES parcels(id),
    amount DECIMAL(10,2),
    method_id INT REFERENCES payment_methods(id),
    payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted BOOLEAN DEFAULT FALSE,
    last_modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE routes (
    id SERIAL PRIMARY KEY,
    start_branch_id INT REFERENCES branches(id),
    end_branch_id INT REFERENCES branches(id),
    distance_km DECIMAL(10,2)
);

CREATE TABLE route_schedules (
    id SERIAL PRIMARY KEY,
    route_id INT REFERENCES routes(id),
    vehicle_id INT REFERENCES vehicles(id),
    departure_time TIMESTAMP
);

CREATE TABLE reviews (
    id SERIAL PRIMARY KEY,
    user_id INT REFERENCES users(id),
    parcel_id INT REFERENCES parcels(id),
    rating INT CHECK (rating BETWEEN 1 AND 5),
    comment TEXT
);

-- Пункт 6: Створення індексів (2 різні типи)
-- Тип 1: B-Tree (стандартний для пошуку діапазонів, сортування та FK)
CREATE INDEX idx_parcels_sender ON parcels USING btree(sender_id);
CREATE INDEX idx_parcels_status ON parcels USING btree(status_id);

-- Тип 2: Hash (ідеально для точного пошуку за рівністю, O(1) складність)
CREATE INDEX idx_parcels_tracking_hash ON parcels USING hash(tracking_number);