package com.ebudosky.post_system.config;

import com.ebudosky.post_system.model.manifest.ParcelManifestDocument;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Автоматична ініціалізація таблиць та індексів у PostgreSQL та MongoDB.
 * Гарантує, що і базова схема ЛР1, і розширена схема ЛР2 (5 таблиць),
 * а також індекси MongoDB завжди створені перед запуском тестів або бенчмарків.
 */
@Component
public class DatabaseSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate, MongoTemplate mongoTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void initializeSchemas() {
        initPostgresSchema();
        initMongoIndexes();
    }

    private void initPostgresSchema() {
        Integer rolesTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'roles'",
                Integer.class
        );

        if (rolesTableCount == null || rolesTableCount == 0) {
            executeSqlFileIfPresent("docs/schema_init.sql");
            executeSqlFileIfPresent("docs/views_procedures_triggers.sql");
            executeSqlFileIfPresent("docs/test_init_data.sql");
        } else {
            Integer rolesRowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM roles", Integer.class);
            if (rolesRowCount != null && rolesRowCount == 0) {
                executeSqlFileIfPresent("docs/test_init_data.sql");
            }
        }

        // Завжди виконуємо ідемпотентне розширення ЛР2 (CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS)
        executeSqlFileIfPresent("docs/lr2_sql_extension.sql");
    }

    private void executeSqlFileIfPresent(String relativePath) {
        try {
            Path path = Path.of(relativePath);
            if (!Files.exists(path)) {
                path = Path.of("ЛР2/post_system", relativePath);
            }
            if (Files.exists(path)) {
                String sql = Files.readString(path);
                jdbcTemplate.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Помилка виконання SQL-скрипту: " + relativePath, e);
        }
    }

    public void initMongoIndexes() {
        IndexOperations indexOps = mongoTemplate.indexOps(ParcelManifestDocument.class);
        indexOps.createIndex(new Index().on("trackingNumber", Sort.Direction.ASC).unique().named("trackingNumber"));
        indexOps.createIndex(new CompoundIndexDefinition(
                new Document("senderId", 1).append("statusId", 1)
        ).named("idx_sender_status"));
        indexOps.createIndex(new CompoundIndexDefinition(
                new Document("specification.deliveryType", 1).append("specification.fragile", 1)
        ).named("idx_spec_delivery_fragile"));
        indexOps.createIndex(new CompoundIndexDefinition(
                new Document("items.category", 1).append("items.hsCode", 1)
        ).named("idx_items_category_hs"));
        indexOps.createIndex(new Index().on("telemetryEvents.shockGForce", Sort.Direction.ASC).named("idx_telemetry_shock"));
    }
}
