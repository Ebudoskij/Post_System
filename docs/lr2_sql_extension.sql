-- ============================================================================
-- Лабораторна робота №2: Розширення реляційної схеми PostgreSQL
-- Відображення документа MongoDB (ParcelManifestDocument) у 5 нормалізованих таблиць:
-- 1. parcels (базова таблиця з ЛР1)
-- 2. parcel_specifications (1:1 — фізичні параметри, упаковка, температурний режим)
-- 3. customs_declarations (1:1 — митні дані та інвойс)
-- 4. parcel_items (1:N — опис кожного товару/вкладення всередині посилки)
-- 5. tracking_telemetry (1:N — розширені події маршруту з GPS та IoT-датчиками)
-- ============================================================================

CREATE TABLE IF NOT EXISTS parcel_specifications (
    id BIGSERIAL PRIMARY KEY,
    parcel_id INT NOT NULL UNIQUE REFERENCES parcels(id) ON DELETE CASCADE,
    delivery_type VARCHAR(50) NOT NULL,
    packaging_type VARCHAR(50) NOT NULL,
    length_cm DECIMAL(8,2) NOT NULL,
    width_cm DECIMAL(8,2) NOT NULL,
    height_cm DECIMAL(8,2) NOT NULL,
    is_fragile BOOLEAN DEFAULT FALSE,
    requires_temp_control BOOLEAN DEFAULT FALSE,
    min_temp_celsius DECIMAL(5,2),
    max_temp_celsius DECIMAL(5,2),
    declared_value DECIMAL(12,2) NOT NULL,
    special_instructions TEXT
);

CREATE TABLE IF NOT EXISTS customs_declarations (
    id BIGSERIAL PRIMARY KEY,
    parcel_id INT NOT NULL UNIQUE REFERENCES parcels(id) ON DELETE CASCADE,
    declaration_number VARCHAR(60) NOT NULL,
    origin_country VARCHAR(3) NOT NULL,
    destination_country VARCHAR(3) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_customs_value DECIMAL(12,2) NOT NULL,
    incoterms VARCHAR(10) NOT NULL,
    export_purpose VARCHAR(50) NOT NULL,
    customs_cleared BOOLEAN DEFAULT FALSE,
    inspected_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS parcel_items (
    id BIGSERIAL PRIMARY KEY,
    parcel_id INT NOT NULL REFERENCES parcels(id) ON DELETE CASCADE,
    item_name VARCHAR(150) NOT NULL,
    hs_code VARCHAR(20) NOT NULL,
    category VARCHAR(60) NOT NULL,
    quantity INT NOT NULL,
    unit_weight_kg DECIMAL(8,3) NOT NULL,
    unit_value DECIMAL(10,2) NOT NULL,
    manufacturer_country VARCHAR(3)
);

CREATE TABLE IF NOT EXISTS tracking_telemetry (
    id BIGSERIAL PRIMARY KEY,
    parcel_id INT NOT NULL REFERENCES parcels(id) ON DELETE CASCADE,
    status_id INT NOT NULL REFERENCES parcel_statuses(id),
    branch_id INT NOT NULL REFERENCES branches(id),
    hub_name VARCHAR(120) NOT NULL,
    scanner_device_id VARCHAR(50) NOT NULL,
    operator_code VARCHAR(50),
    gps_lat DECIMAL(9,6) NOT NULL,
    gps_lon DECIMAL(9,6) NOT NULL,
    temperature_c DECIMAL(5,2),
    humidity_percent DECIMAL(5,2),
    shock_g_force DECIMAL(5,2),
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(255)
);

-- Індекси для забезпечення чесного порівняння швидкодії з MongoDB
CREATE INDEX IF NOT EXISTS idx_parcel_specs_parcel_id ON parcel_specifications USING btree(parcel_id);
CREATE INDEX IF NOT EXISTS idx_parcel_specs_delivery_fragile ON parcel_specifications USING btree(delivery_type, is_fragile);

CREATE INDEX IF NOT EXISTS idx_customs_parcel_id ON customs_declarations USING btree(parcel_id);
CREATE INDEX IF NOT EXISTS idx_customs_dest_country ON customs_declarations USING btree(destination_country);

CREATE INDEX IF NOT EXISTS idx_parcel_items_parcel_id ON parcel_items USING btree(parcel_id);
CREATE INDEX IF NOT EXISTS idx_parcel_items_category_hs ON parcel_items USING btree(category, hs_code);

CREATE INDEX IF NOT EXISTS idx_tracking_telemetry_parcel_id ON tracking_telemetry USING btree(parcel_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_tracking_telemetry_shock ON tracking_telemetry USING btree(shock_g_force);
