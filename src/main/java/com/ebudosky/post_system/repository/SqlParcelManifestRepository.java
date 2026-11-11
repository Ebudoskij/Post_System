package com.ebudosky.post_system.repository;

import com.ebudosky.post_system.model.manifest.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Реляційний репозиторій (PostgreSQL), який працює з 5 нормалізованими таблицями:
 * 1. parcels
 * 2. parcel_specifications (1:1)
 * 3. customs_declarations (1:1)
 * 4. parcel_items (1:N)
 * 5. tracking_telemetry (1:N)
 */
@Repository
public class SqlParcelManifestRepository {

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public SqlParcelManifestRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Збереження одного повного маніфесту посилки у 5 таблиць SQL в межах транзакції.
     */
    @Transactional
    public Long saveManifest(ParcelManifestDocument doc) {
        Long parcelId = jdbcClient.sql("""
                        INSERT INTO parcels (tracking_number, sender_id, receiver_id, weight, status_id, is_deleted, last_modified_at)
                        VALUES (:trn, :sender, :receiver, :weight, :status, :deleted, :modified)
                        RETURNING id
                        """)
                .param("trn", doc.getTrackingNumber())
                .param("sender", doc.getSenderId())
                .param("receiver", doc.getReceiverId())
                .param("weight", doc.getWeight())
                .param("status", doc.getStatusId())
                .param("deleted", doc.isDeleted())
                .param("modified", Timestamp.valueOf(doc.getLastModifiedAt() != null ? doc.getLastModifiedAt() : LocalDateTime.now()))
                .query(Long.class)
                .single();

        doc.setSqlParcelId(parcelId);

        ParcelSpecification spec = doc.getSpecification();
        if (spec != null) {
            jdbcClient.sql("""
                            INSERT INTO parcel_specifications (
                                parcel_id, delivery_type, packaging_type, length_cm, width_cm, height_cm,
                                is_fragile, requires_temp_control, min_temp_celsius, max_temp_celsius,
                                declared_value, special_instructions
                            ) VALUES (
                                :pid, :dtype, :ptype, :len, :wid, :hei,
                                :fragile, :tempCtrl, :minTemp, :maxTemp, :decVal, :instr
                            )
                            """)
                    .param("pid", parcelId)
                    .param("dtype", spec.deliveryType())
                    .param("ptype", spec.packagingType())
                    .param("len", spec.lengthCm())
                    .param("wid", spec.widthCm())
                    .param("hei", spec.heightCm())
                    .param("fragile", spec.fragile())
                    .param("tempCtrl", spec.requiresTempControl())
                    .param("minTemp", spec.minTempCelsius())
                    .param("maxTemp", spec.maxTempCelsius())
                    .param("decVal", spec.declaredValue())
                    .param("instr", spec.specialInstructions())
                    .update();
        }

        CustomsDeclaration cd = doc.getCustomsDeclaration();
        if (cd != null) {
            jdbcClient.sql("""
                            INSERT INTO customs_declarations (
                                parcel_id, declaration_number, origin_country, destination_country,
                                currency, total_customs_value, incoterms, export_purpose,
                                customs_cleared, inspected_at
                            ) VALUES (
                                :pid, :dnum, :orig, :dest, :curr, :val, :inco, :purp, :cleared, :insp
                            )
                            """)
                    .param("pid", parcelId)
                    .param("dnum", cd.declarationNumber())
                    .param("orig", cd.originCountry())
                    .param("dest", cd.destinationCountry())
                    .param("curr", cd.currency())
                    .param("val", cd.totalCustomsValue())
                    .param("inco", cd.incoterms())
                    .param("purp", cd.exportPurpose())
                    .param("cleared", cd.customsCleared())
                    .param("insp", cd.inspectedAt() != null ? Timestamp.valueOf(cd.inspectedAt()) : null)
                    .update();
        }

        if (doc.getItems() != null && !doc.getItems().isEmpty()) {
            jdbcTemplate.batchUpdate("""
                            INSERT INTO parcel_items (
                                parcel_id, item_name, hs_code, category, quantity, unit_weight_kg, unit_value, manufacturer_country
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    doc.getItems(),
                    doc.getItems().size(),
                    (ps, item) -> {
                        ps.setLong(1, parcelId);
                        ps.setString(2, item.itemName());
                        ps.setString(3, item.hsCode());
                        ps.setString(4, item.category());
                        ps.setInt(5, item.quantity());
                        ps.setBigDecimal(6, item.unitWeightKg());
                        ps.setBigDecimal(7, item.unitValue());
                        ps.setString(8, item.manufacturerCountry());
                    });
        }

        if (doc.getTelemetryEvents() != null && !doc.getTelemetryEvents().isEmpty()) {
            jdbcTemplate.batchUpdate("""
                            INSERT INTO tracking_telemetry (
                                parcel_id, status_id, branch_id, hub_name, scanner_device_id, operator_code,
                                gps_lat, gps_lon, temperature_c, humidity_percent, shock_g_force, recorded_at, notes
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    doc.getTelemetryEvents(),
                    doc.getTelemetryEvents().size(),
                    (ps, ev) -> {
                        ps.setLong(1, parcelId);
                        ps.setLong(2, ev.statusId());
                        ps.setLong(3, ev.branchId());
                        ps.setString(4, ev.hubName());
                        ps.setString(5, ev.scannerDeviceId());
                        ps.setString(6, ev.operatorCode());
                        ps.setBigDecimal(7, ev.gpsLat());
                        ps.setBigDecimal(8, ev.gpsLon());
                        ps.setBigDecimal(9, ev.temperatureC());
                        ps.setBigDecimal(10, ev.humidityPercent());
                        ps.setBigDecimal(11, ev.shockGForce());
                        ps.setTimestamp(12, Timestamp.valueOf(ev.recordedAt()));
                        ps.setString(13, ev.notes());
                    });
        }

        return parcelId;
    }

    /**
     * Високопродуктивна пакетна вставка (Batch Insert) у всі 5 таблиць PostgreSQL.
     * Використовує JDBC Batch Update + reWriteBatchedInserts=true для чесного змагання з MongoDB insertMany().
     */
    @Transactional
    public void saveBatch(List<ParcelManifestDocument> docs) {
        if (docs.isEmpty()) {
            return;
        }

        // 1. Вставка у таблицю parcels
        jdbcTemplate.batchUpdate("""
                        INSERT INTO parcels (tracking_number, sender_id, receiver_id, weight, status_id, is_deleted, last_modified_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                docs,
                docs.size(),
                (ps, doc) -> {
                    ps.setString(1, doc.getTrackingNumber());
                    ps.setLong(2, doc.getSenderId());
                    ps.setLong(3, doc.getReceiverId());
                    ps.setBigDecimal(4, doc.getWeight());
                    ps.setLong(5, doc.getStatusId());
                    ps.setBoolean(6, doc.isDeleted());
                    ps.setTimestamp(7, Timestamp.valueOf(doc.getLastModifiedAt() != null ? doc.getLastModifiedAt() : LocalDateTime.now()));
                });

        // Отримуємо згенеровані ID для вставлених tracking_number одним запитом
        List<String> trackingNumbers = docs.stream().map(ParcelManifestDocument::getTrackingNumber).toList();
        Map<String, Long> trnToIdMap = new HashMap<>(docs.size() * 2);
        jdbcClient.sql("SELECT tracking_number, id FROM parcels WHERE tracking_number IN (:trns)")
                .param("trns", trackingNumbers)
                .query((rs) -> {
                    trnToIdMap.put(rs.getString(1), rs.getLong(2));
                });

        for (ParcelManifestDocument doc : docs) {
            doc.setSqlParcelId(trnToIdMap.get(doc.getTrackingNumber()));
        }

        // 2. Пакетна вставка у parcel_specifications (1:1)
        jdbcTemplate.batchUpdate("""
                        INSERT INTO parcel_specifications (
                            parcel_id, delivery_type, packaging_type, length_cm, width_cm, height_cm,
                            is_fragile, requires_temp_control, min_temp_celsius, max_temp_celsius,
                            declared_value, special_instructions
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                docs,
                docs.size(),
                (ps, doc) -> {
                    ParcelSpecification s = doc.getSpecification();
                    ps.setLong(1, doc.getSqlParcelId());
                    ps.setString(2, s.deliveryType());
                    ps.setString(3, s.packagingType());
                    ps.setBigDecimal(4, s.lengthCm());
                    ps.setBigDecimal(5, s.widthCm());
                    ps.setBigDecimal(6, s.heightCm());
                    ps.setBoolean(7, s.fragile());
                    ps.setBoolean(8, s.requiresTempControl());
                    ps.setBigDecimal(9, s.minTempCelsius());
                    ps.setBigDecimal(10, s.maxTempCelsius());
                    ps.setBigDecimal(11, s.declaredValue());
                    ps.setString(12, s.specialInstructions());
                });

        // 3. Пакетна вставка у customs_declarations (1:1)
        jdbcTemplate.batchUpdate("""
                        INSERT INTO customs_declarations (
                            parcel_id, declaration_number, origin_country, destination_country,
                            currency, total_customs_value, incoterms, export_purpose,
                            customs_cleared, inspected_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                docs,
                docs.size(),
                (ps, doc) -> {
                    CustomsDeclaration cd = doc.getCustomsDeclaration();
                    ps.setLong(1, doc.getSqlParcelId());
                    ps.setString(2, cd.declarationNumber());
                    ps.setString(3, cd.originCountry());
                    ps.setString(4, cd.destinationCountry());
                    ps.setString(5, cd.currency());
                    ps.setBigDecimal(6, cd.totalCustomsValue());
                    ps.setString(7, cd.incoterms());
                    ps.setString(8, cd.exportPurpose());
                    ps.setBoolean(9, cd.customsCleared());
                    ps.setTimestamp(10, cd.inspectedAt() != null ? Timestamp.valueOf(cd.inspectedAt()) : null);
                });

        // 4. Пакетна вставка у parcel_items (1:N)
        record ItemWithParcelId(Long parcelId, ParcelItem item) {}
        List<ItemWithParcelId> allItems = new ArrayList<>(docs.size() * 4);
        for (ParcelManifestDocument doc : docs) {
            if (doc.getItems() != null) {
                for (ParcelItem item : doc.getItems()) {
                    allItems.add(new ItemWithParcelId(doc.getSqlParcelId(), item));
                }
            }
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO parcel_items (
                            parcel_id, item_name, hs_code, category, quantity, unit_weight_kg, unit_value, manufacturer_country
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                allItems,
                2000,
                (ps, pair) -> {
                    ParcelItem item = pair.item();
                    ps.setLong(1, pair.parcelId());
                    ps.setString(2, item.itemName());
                    ps.setString(3, item.hsCode());
                    ps.setString(4, item.category());
                    ps.setInt(5, item.quantity());
                    ps.setBigDecimal(6, item.unitWeightKg());
                    ps.setBigDecimal(7, item.unitValue());
                    ps.setString(8, item.manufacturerCountry());
                });

        // 5. Пакетна вставка у tracking_telemetry (1:N)
        record TelemetryWithParcelId(Long parcelId, TrackingTelemetryEvent event) {}
        List<TelemetryWithParcelId> allEvents = new ArrayList<>(docs.size() * 6);
        for (ParcelManifestDocument doc : docs) {
            if (doc.getTelemetryEvents() != null) {
                for (TrackingTelemetryEvent ev : doc.getTelemetryEvents()) {
                    allEvents.add(new TelemetryWithParcelId(doc.getSqlParcelId(), ev));
                }
            }
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO tracking_telemetry (
                            parcel_id, status_id, branch_id, hub_name, scanner_device_id, operator_code,
                            gps_lat, gps_lon, temperature_c, humidity_percent, shock_g_force, recorded_at, notes
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                allEvents,
                2000,
                (ps, pair) -> {
                    TrackingTelemetryEvent ev = pair.event();
                    ps.setLong(1, pair.parcelId());
                    ps.setLong(2, ev.statusId());
                    ps.setLong(3, ev.branchId());
                    ps.setString(4, ev.hubName());
                    ps.setString(5, ev.scannerDeviceId());
                    ps.setString(6, ev.operatorCode());
                    ps.setBigDecimal(7, ev.gpsLat());
                    ps.setBigDecimal(8, ev.gpsLon());
                    ps.setBigDecimal(9, ev.temperatureC());
                    ps.setBigDecimal(10, ev.humidityPercent());
                    ps.setBigDecimal(11, ev.shockGForce());
                    ps.setTimestamp(12, Timestamp.valueOf(ev.recordedAt()));
                    ps.setString(13, ev.notes());
                });
    }

    /**
     * Спосіб 1 читання агрегата з 5 таблиць SQL (Multi-Query / Avoid Cartesian Explosion):
     * 1 запит із JOIN для таблиць 1:1 (parcels + parcel_specifications + customs_declarations)
     * + 2 індексовані запити для таблиць 1:N (parcel_items та tracking_telemetry).
     */
    public Optional<ParcelManifestDocument> findByTrackingNumber(String trackingNumber) {
        List<ParcelManifestDocument> baseList = jdbcClient.sql("""
                        SELECT
                            p.id AS p_id, p.tracking_number, p.sender_id, p.receiver_id, p.weight, p.status_id, p.is_deleted, p.last_modified_at,
                            ps.delivery_type, ps.packaging_type, ps.length_cm, ps.width_cm, ps.height_cm,
                            ps.is_fragile, ps.requires_temp_control, ps.min_temp_celsius, ps.max_temp_celsius, ps.declared_value, ps.special_instructions,
                            cd.declaration_number, cd.origin_country, cd.destination_country, cd.currency,
                            cd.total_customs_value, cd.incoterms, cd.export_purpose, cd.customs_cleared, cd.inspected_at
                        FROM parcels p
                        LEFT JOIN parcel_specifications ps ON ps.parcel_id = p.id
                        LEFT JOIN customs_declarations cd ON cd.parcel_id = p.id
                        WHERE p.tracking_number = :trn
                        """)
                .param("trn", trackingNumber)
                .query((rs, rowNum) -> {
                    ParcelSpecification spec = new ParcelSpecification(
                            rs.getString("delivery_type"),
                            rs.getString("packaging_type"),
                            rs.getBigDecimal("length_cm"),
                            rs.getBigDecimal("width_cm"),
                            rs.getBigDecimal("height_cm"),
                            rs.getBoolean("is_fragile"),
                            rs.getBoolean("requires_temp_control"),
                            rs.getBigDecimal("min_temp_celsius"),
                            rs.getBigDecimal("max_temp_celsius"),
                            rs.getBigDecimal("declared_value"),
                            rs.getString("special_instructions")
                    );
                    Timestamp inspectedTs = rs.getTimestamp("inspected_at");
                    CustomsDeclaration customs = new CustomsDeclaration(
                            rs.getString("declaration_number"),
                            rs.getString("origin_country"),
                            rs.getString("destination_country"),
                            rs.getString("currency"),
                            rs.getBigDecimal("total_customs_value"),
                            rs.getString("incoterms"),
                            rs.getString("export_purpose"),
                            rs.getBoolean("customs_cleared"),
                            inspectedTs != null ? inspectedTs.toLocalDateTime() : null
                    );
                    Timestamp modTs = rs.getTimestamp("last_modified_at");
                    return new ParcelManifestDocument(
                            null,
                            rs.getLong("p_id"),
                            rs.getString("tracking_number"),
                            rs.getLong("sender_id"),
                            rs.getLong("receiver_id"),
                            rs.getBigDecimal("weight"),
                            rs.getLong("status_id"),
                            rs.getBoolean("is_deleted"),
                            modTs != null ? modTs.toLocalDateTime() : null,
                            spec,
                            customs,
                            new ArrayList<>(),
                            new ArrayList<>()
                    );
                })
                .list();

        if (baseList.isEmpty()) {
            return Optional.empty();
        }

        ParcelManifestDocument doc = baseList.getFirst();
        Long parcelId = doc.getSqlParcelId();

        List<ParcelItem> items = jdbcClient.sql("""
                        SELECT item_name, hs_code, category, quantity, unit_weight_kg, unit_value, manufacturer_country
                        FROM parcel_items
                        WHERE parcel_id = :pid
                        ORDER BY id
                        """)
                .param("pid", parcelId)
                .query((rs, rowNum) -> new ParcelItem(
                        rs.getString("item_name"),
                        rs.getString("hs_code"),
                        rs.getString("category"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_weight_kg"),
                        rs.getBigDecimal("unit_value"),
                        rs.getString("manufacturer_country")
                ))
                .list();

        List<TrackingTelemetryEvent> events = jdbcClient.sql("""
                        SELECT status_id, branch_id, hub_name, scanner_device_id, operator_code,
                               gps_lat, gps_lon, temperature_c, humidity_percent, shock_g_force, recorded_at, notes
                        FROM tracking_telemetry
                        WHERE parcel_id = :pid
                        ORDER BY recorded_at ASC
                        """)
                .param("pid", parcelId)
                .query((rs, rowNum) -> new TrackingTelemetryEvent(
                        rs.getLong("status_id"),
                        rs.getLong("branch_id"),
                        rs.getString("hub_name"),
                        rs.getString("scanner_device_id"),
                        rs.getString("operator_code"),
                        rs.getBigDecimal("gps_lat"),
                        rs.getBigDecimal("gps_lon"),
                        rs.getBigDecimal("temperature_c"),
                        rs.getBigDecimal("humidity_percent"),
                        rs.getBigDecimal("shock_g_force"),
                        rs.getTimestamp("recorded_at").toLocalDateTime(),
                        rs.getString("notes")
                ))
                .list();

        doc.setItems(items);
        doc.setTelemetryEvents(events);
        return Optional.of(doc);
    }

    /**
     * Спосіб 2 читання агрегата з 5 таблиць SQL: ОДИН великий запит з 5-way LEFT JOIN.
     * Демонструє класичну проблему реляційних БД при паралельних зв'язках 1:N (items × telemetry = декартовий добуток рядків),
     * які доводиться дедуплікувати у пам'яті застосунку.
     */
    public Optional<ParcelManifestDocument> findByTrackingNumberSingleFiveWayJoin(String trackingNumber) {
        return jdbcClient.sql("""
                        SELECT
                            p.id AS p_id, p.tracking_number, p.sender_id, p.receiver_id, p.weight, p.status_id, p.is_deleted, p.last_modified_at,
                            ps.delivery_type, ps.packaging_type, ps.length_cm, ps.width_cm, ps.height_cm,
                            ps.is_fragile, ps.requires_temp_control, ps.min_temp_celsius, ps.max_temp_celsius, ps.declared_value, ps.special_instructions,
                            cd.declaration_number, cd.origin_country, cd.destination_country, cd.currency,
                            cd.total_customs_value, cd.incoterms, cd.export_purpose, cd.customs_cleared, cd.inspected_at,
                            pi.id AS item_id, pi.item_name, pi.hs_code, pi.category, pi.quantity, pi.unit_weight_kg, pi.unit_value, pi.manufacturer_country,
                            tt.id AS tel_id, tt.status_id AS tel_status_id, tt.branch_id AS tel_branch_id, tt.hub_name, tt.scanner_device_id,
                            tt.operator_code, tt.gps_lat, tt.gps_lon, tt.temperature_c, tt.humidity_percent, tt.shock_g_force, tt.recorded_at, tt.notes
                        FROM parcels p
                        LEFT JOIN parcel_specifications ps ON ps.parcel_id = p.id
                        LEFT JOIN customs_declarations cd ON cd.parcel_id = p.id
                        LEFT JOIN parcel_items pi ON pi.parcel_id = p.id
                        LEFT JOIN tracking_telemetry tt ON tt.parcel_id = p.id
                        WHERE p.tracking_number = :trn
                        """)
                .param("trn", trackingNumber)
                .query(rs -> {
                    ParcelManifestDocument doc = null;
                    Map<Long, ParcelItem> itemsMap = new LinkedHashMap<>();
                    Map<Long, TrackingTelemetryEvent> eventsMap = new LinkedHashMap<>();

                    while (rs.next()) {
                        if (doc == null) {
                            ParcelSpecification spec = new ParcelSpecification(
                                    rs.getString("delivery_type"),
                                    rs.getString("packaging_type"),
                                    rs.getBigDecimal("length_cm"),
                                    rs.getBigDecimal("width_cm"),
                                    rs.getBigDecimal("height_cm"),
                                    rs.getBoolean("is_fragile"),
                                    rs.getBoolean("requires_temp_control"),
                                    rs.getBigDecimal("min_temp_celsius"),
                                    rs.getBigDecimal("max_temp_celsius"),
                                    rs.getBigDecimal("declared_value"),
                                    rs.getString("special_instructions")
                            );
                            Timestamp inspectedTs = rs.getTimestamp("inspected_at");
                            CustomsDeclaration customs = new CustomsDeclaration(
                                    rs.getString("declaration_number"),
                                    rs.getString("origin_country"),
                                    rs.getString("destination_country"),
                                    rs.getString("currency"),
                                    rs.getBigDecimal("total_customs_value"),
                                    rs.getString("incoterms"),
                                    rs.getString("export_purpose"),
                                    rs.getBoolean("customs_cleared"),
                                    inspectedTs != null ? inspectedTs.toLocalDateTime() : null
                            );
                            Timestamp modTs = rs.getTimestamp("last_modified_at");
                            doc = new ParcelManifestDocument(
                                    null,
                                    rs.getLong("p_id"),
                                    rs.getString("tracking_number"),
                                    rs.getLong("sender_id"),
                                    rs.getLong("receiver_id"),
                                    rs.getBigDecimal("weight"),
                                    rs.getLong("status_id"),
                                    rs.getBoolean("is_deleted"),
                                    modTs != null ? modTs.toLocalDateTime() : null,
                                    spec,
                                    customs,
                                    new ArrayList<>(),
                                    new ArrayList<>()
                            );
                        }

                        long itemId = rs.getLong("item_id");
                        if (!rs.wasNull() && !itemsMap.containsKey(itemId)) {
                            itemsMap.put(itemId, new ParcelItem(
                                    rs.getString("item_name"),
                                    rs.getString("hs_code"),
                                    rs.getString("category"),
                                    rs.getInt("quantity"),
                                    rs.getBigDecimal("unit_weight_kg"),
                                    rs.getBigDecimal("unit_value"),
                                    rs.getString("manufacturer_country")
                            ));
                        }

                        long telId = rs.getLong("tel_id");
                        if (!rs.wasNull() && !eventsMap.containsKey(telId)) {
                            eventsMap.put(telId, new TrackingTelemetryEvent(
                                    rs.getLong("tel_status_id"),
                                    rs.getLong("tel_branch_id"),
                                    rs.getString("hub_name"),
                                    rs.getString("scanner_device_id"),
                                    rs.getString("operator_code"),
                                    rs.getBigDecimal("gps_lat"),
                                    rs.getBigDecimal("gps_lon"),
                                    rs.getBigDecimal("temperature_c"),
                                    rs.getBigDecimal("humidity_percent"),
                                    rs.getBigDecimal("shock_g_force"),
                                    rs.getTimestamp("recorded_at").toLocalDateTime(),
                                    rs.getString("notes")
                            ));
                        }
                    }

                    if (doc == null) {
                        return Optional.empty();
                    }
                    doc.setItems(new ArrayList<>(itemsMap.values()));
                    doc.setTelemetryEvents(new ArrayList<>(eventsMap.values()));
                    return Optional.of(doc);
                });
    }

    /**
     * Пакетне читання списку з N повних маніфестів (напр. 50 посилок рейсу) через 5-way LEFT JOIN.
     * Для 50 посилок (по 4 товари × 6 подій трекінгу) SQL повертає 50 × 24 = 1 200 рядків декартового добутку!
     */
    public List<ParcelManifestDocument> findByTrackingNumbersInFiveWayJoin(List<String> trackingNumbers) {
        return jdbcClient.sql("""
                        SELECT
                            p.id AS p_id, p.tracking_number, p.sender_id, p.receiver_id, p.weight, p.status_id, p.is_deleted, p.last_modified_at,
                            ps.delivery_type, ps.packaging_type, ps.length_cm, ps.width_cm, ps.height_cm,
                            ps.is_fragile, ps.requires_temp_control, ps.min_temp_celsius, ps.max_temp_celsius, ps.declared_value, ps.special_instructions,
                            cd.declaration_number, cd.origin_country, cd.destination_country, cd.currency,
                            cd.total_customs_value, cd.incoterms, cd.export_purpose, cd.customs_cleared, cd.inspected_at,
                            pi.id AS item_id, pi.item_name, pi.hs_code, pi.category, pi.quantity, pi.unit_weight_kg, pi.unit_value, pi.manufacturer_country,
                            tt.id AS tel_id, tt.status_id AS tel_status_id, tt.branch_id AS tel_branch_id, tt.hub_name, tt.scanner_device_id,
                            tt.operator_code, tt.gps_lat, tt.gps_lon, tt.temperature_c, tt.humidity_percent, tt.shock_g_force, tt.recorded_at, tt.notes
                        FROM parcels p
                        LEFT JOIN parcel_specifications ps ON ps.parcel_id = p.id
                        LEFT JOIN customs_declarations cd ON cd.parcel_id = p.id
                        LEFT JOIN parcel_items pi ON pi.parcel_id = p.id
                        LEFT JOIN tracking_telemetry tt ON tt.parcel_id = p.id
                        WHERE p.tracking_number IN (:trns)
                        """)
                .param("trns", trackingNumbers)
                .query(rs -> {
                    Map<String, ParcelManifestDocument> docsMap = new LinkedHashMap<>();
                    Map<String, Map<Long, ParcelItem>> itemsByTrn = new HashMap<>();
                    Map<String, Map<Long, TrackingTelemetryEvent>> eventsByTrn = new HashMap<>();

                    while (rs.next()) {
                        String trn = rs.getString("tracking_number");
                        if (!docsMap.containsKey(trn)) {
                            ParcelSpecification spec = new ParcelSpecification(
                                    rs.getString("delivery_type"),
                                    rs.getString("packaging_type"),
                                    rs.getBigDecimal("length_cm"),
                                    rs.getBigDecimal("width_cm"),
                                    rs.getBigDecimal("height_cm"),
                                    rs.getBoolean("is_fragile"),
                                    rs.getBoolean("requires_temp_control"),
                                    rs.getBigDecimal("min_temp_celsius"),
                                    rs.getBigDecimal("max_temp_celsius"),
                                    rs.getBigDecimal("declared_value"),
                                    rs.getString("special_instructions")
                            );
                            Timestamp inspectedTs = rs.getTimestamp("inspected_at");
                            CustomsDeclaration customs = new CustomsDeclaration(
                                    rs.getString("declaration_number"),
                                    rs.getString("origin_country"),
                                    rs.getString("destination_country"),
                                    rs.getString("currency"),
                                    rs.getBigDecimal("total_customs_value"),
                                    rs.getString("incoterms"),
                                    rs.getString("export_purpose"),
                                    rs.getBoolean("customs_cleared"),
                                    inspectedTs != null ? inspectedTs.toLocalDateTime() : null
                            );
                            Timestamp modTs = rs.getTimestamp("last_modified_at");
                            docsMap.put(trn, new ParcelManifestDocument(
                                    null,
                                    rs.getLong("p_id"),
                                    trn,
                                    rs.getLong("sender_id"),
                                    rs.getLong("receiver_id"),
                                    rs.getBigDecimal("weight"),
                                    rs.getLong("status_id"),
                                    rs.getBoolean("is_deleted"),
                                    modTs != null ? modTs.toLocalDateTime() : null,
                                    spec,
                                    customs,
                                    new ArrayList<>(),
                                    new ArrayList<>()
                            ));
                            itemsByTrn.put(trn, new LinkedHashMap<>());
                            eventsByTrn.put(trn, new LinkedHashMap<>());
                        }

                        long itemId = rs.getLong("item_id");
                        if (!rs.wasNull()) {
                            itemsByTrn.get(trn).putIfAbsent(itemId, new ParcelItem(
                                    rs.getString("item_name"),
                                    rs.getString("hs_code"),
                                    rs.getString("category"),
                                    rs.getInt("quantity"),
                                    rs.getBigDecimal("unit_weight_kg"),
                                    rs.getBigDecimal("unit_value"),
                                    rs.getString("manufacturer_country")
                            ));
                        }

                        long telId = rs.getLong("tel_id");
                        if (!rs.wasNull()) {
                            eventsByTrn.get(trn).putIfAbsent(telId, new TrackingTelemetryEvent(
                                    rs.getLong("tel_status_id"),
                                    rs.getLong("tel_branch_id"),
                                    rs.getString("hub_name"),
                                    rs.getString("scanner_device_id"),
                                    rs.getString("operator_code"),
                                    rs.getBigDecimal("gps_lat"),
                                    rs.getBigDecimal("gps_lon"),
                                    rs.getBigDecimal("temperature_c"),
                                    rs.getBigDecimal("humidity_percent"),
                                    rs.getBigDecimal("shock_g_force"),
                                    rs.getTimestamp("recorded_at").toLocalDateTime(),
                                    rs.getString("notes")
                            ));
                        }
                    }

                    for (Map.Entry<String, ParcelManifestDocument> entry : docsMap.entrySet()) {
                        String trn = entry.getKey();
                        entry.getValue().setItems(new ArrayList<>(itemsByTrn.get(trn).values()));
                        entry.getValue().setTelemetryEvents(new ArrayList<>(eventsByTrn.get(trn).values()));
                    }
                    return new ArrayList<>(docsMap.values());
                });
    }

    /**
     * Додавання нової події телеметрії в реальному часі (INSERT у дочірню таблицю + UPDATE статусу в parcels).
     */
    @Transactional
    public void appendTelemetryEvent(String trackingNumber, TrackingTelemetryEvent event) {
        jdbcClient.sql("""
                        WITH updated_parcel AS (
                            UPDATE parcels
                            SET status_id = :statusId, last_modified_at = CURRENT_TIMESTAMP
                            WHERE tracking_number = :trn
                            RETURNING id
                        )
                        INSERT INTO tracking_telemetry (
                            parcel_id, status_id, branch_id, hub_name, scanner_device_id, operator_code,
                            gps_lat, gps_lon, temperature_c, humidity_percent, shock_g_force, recorded_at, notes
                        )
                        SELECT
                            id, :statusId, :branchId, :hubName, :scannerId, :opCode,
                            :lat, :lon, :temp, :hum, :shock, :recAt, :notes
                        FROM updated_parcel
                        """)
                .param("trn", trackingNumber)
                .param("statusId", event.statusId())
                .param("branchId", event.branchId())
                .param("hubName", event.hubName())
                .param("scannerId", event.scannerDeviceId())
                .param("opCode", event.operatorCode())
                .param("lat", event.gpsLat())
                .param("lon", event.gpsLon())
                .param("temp", event.temperatureC())
                .param("hum", event.humidityPercent())
                .param("shock", event.shockGForce())
                .param("recAt", Timestamp.valueOf(event.recordedAt()))
                .param("notes", event.notes())
                .update();
    }

    /**
     * Складний пошук за атрибутами з різних таблиць (специфікація + вкладення + телеметрія).
     */
    public List<String> findTrackingNumbersByDeepCriteria(
            String deliveryType,
            boolean fragile,
            String itemCategory,
            BigDecimal minShockG,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT DISTINCT p.tracking_number
                        FROM parcels p
                        JOIN parcel_specifications ps ON ps.parcel_id = p.id
                        JOIN parcel_items pi ON pi.parcel_id = p.id
                        JOIN tracking_telemetry tt ON tt.parcel_id = p.id
                        WHERE ps.delivery_type = :dtype
                          AND ps.is_fragile = :fragile
                          AND pi.category = :cat
                          AND tt.shock_g_force >= :minShock
                        LIMIT :lim
                        """)
                .param("dtype", deliveryType)
                .param("fragile", fragile)
                .param("cat", itemCategory)
                .param("minShock", minShockG)
                .param("lim", limit)
                .query(String.class)
                .list();
    }

    /**
     * Аналітична агрегація за типами доставки (JOIN + підзапит кількості подій + GROUP BY).
     */
    public List<DeliveryTypeStats> aggregateStatsByDeliveryType() {
        return jdbcClient.sql("""
                        SELECT
                            ps.delivery_type,
                            COUNT(p.id) AS parcel_count,
                            AVG(p.weight) AS avg_weight,
                            AVG(ps.declared_value) AS avg_declared_value,
                            AVG(COALESCE(tc.cnt, 0)) AS avg_telemetry_cnt
                        FROM parcels p
                        JOIN parcel_specifications ps ON ps.parcel_id = p.id
                        LEFT JOIN (
                            SELECT parcel_id, COUNT(*) AS cnt
                            FROM tracking_telemetry
                            GROUP BY parcel_id
                        ) tc ON tc.parcel_id = p.id
                        WHERE p.tracking_number LIKE 'BMK%'
                        GROUP BY ps.delivery_type
                        ORDER BY parcel_count DESC
                        """)
                .query((rs, rowNum) -> new DeliveryTypeStats(
                        rs.getString("delivery_type"),
                        rs.getLong("parcel_count"),
                        rs.getDouble("avg_weight"),
                        rs.getDouble("avg_declared_value"),
                        rs.getDouble("avg_telemetry_cnt")
                ))
                .list();
    }

    /**
     * Очищення тестових (бенчмарк) записів без видалення початкових 10 посилок з ЛР1.
     */
    @Transactional
    public void clearBenchmarkData() {
        jdbcClient.sql("DELETE FROM parcels WHERE tracking_number LIKE 'BMK%'").update();
    }

    public long countBenchmarkParcels() {
        return jdbcClient.sql("SELECT COUNT(*) FROM parcels WHERE tracking_number LIKE 'BMK%'")
                .query(Long.class)
                .single();
    }
}
