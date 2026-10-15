-- Тригерна функція для оновлення last_modified_at
CREATE OR REPLACE FUNCTION update_timestamp_fn()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_modified_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--  Тригери для сутностей
CREATE TRIGGER trg_users_updated
BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_timestamp_fn();

CREATE TRIGGER trg_employees_updated
BEFORE UPDATE ON employees FOR EACH ROW EXECUTE FUNCTION update_timestamp_fn();

CREATE TRIGGER trg_parcels_updated
BEFORE UPDATE ON parcels FOR EACH ROW EXECUTE FUNCTION update_timestamp_fn();

CREATE TRIGGER trg_payments_updated
BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION update_timestamp_fn();

-- View активних посилок (ігнорує soft-deleted)
CREATE OR REPLACE VIEW v_active_parcels AS
SELECT id, tracking_number, sender_id, receiver_id, weight, status_id, last_modified_at
FROM parcels
WHERE is_deleted = FALSE;

-- View активних користувачів
CREATE OR REPLACE VIEW v_active_users AS
SELECT id, full_name, phone, role_id
FROM users
WHERE is_deleted = FALSE;

-- View детальної історії трекінгу
CREATE OR REPLACE VIEW v_parcel_tracking_details AS
SELECT 
    p.tracking_number, 
    ps.name AS status, 
    b.city AS branch_city, 
    th.recorded_at
FROM tracking_history th
JOIN parcels p ON th.parcel_id = p.id
JOIN parcel_statuses ps ON th.status_id = ps.id
JOIN branches b ON th.branch_id = b.id
WHERE p.is_deleted = FALSE
ORDER BY th.recorded_at DESC;

-- Збережена процедура створення посилки (реєстрація)
CREATE OR REPLACE PROCEDURE sp_create_parcel(
    p_tracking_number VARCHAR(50),
    p_sender_id INT,
    p_receiver_id INT,
    p_weight DECIMAL,
    p_status_id INT,
    OUT p_new_id INT
)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO parcels(tracking_number, sender_id, receiver_id, weight, status_id)
    VALUES (p_tracking_number, p_sender_id, p_receiver_id, p_weight, p_status_id)
    RETURNING id INTO p_new_id;
END;
$$;

-- Збережена процедура додавання історії
CREATE OR REPLACE PROCEDURE sp_add_tracking_history(
    p_parcel_id INT,
    p_status_id INT,
    p_branch_id INT
)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tracking_history(parcel_id, status_id, branch_id)
    VALUES (p_parcel_id, p_status_id, p_branch_id);

    UPDATE parcels SET status_id = p_status_id WHERE id = p_parcel_id;
END;
$$;

-- Збережена процедура Soft Delete для посилки
CREATE OR REPLACE PROCEDURE sp_soft_delete_parcel(p_parcel_id INT)
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE parcels SET is_deleted = TRUE WHERE id = p_parcel_id;
END;
$$;