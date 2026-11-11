package com.ebudosky.post_system.model.manifest;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Головний агрегований документ «Цифровий маніфест та паспорт посилки».
 *
 * У NoSQL (MongoDB) зберігається як 1 самодостатній BSON-документ у колекції "parcel_manifests".
 * У SQL (PostgreSQL) декомпозується на 5 нормалізованих таблиць (3НФ):
 *   1. parcels
 *   2. parcel_specifications
 *   3. customs_declarations
 *   4. parcel_items
 *   5. tracking_telemetry
 */
@Document(collection = "parcel_manifests")
@CompoundIndexes({
        @CompoundIndex(name = "idx_sender_status", def = "{'senderId': 1, 'statusId': 1}"),
        @CompoundIndex(name = "idx_spec_delivery_fragile", def = "{'specification.deliveryType': 1, 'specification.fragile': 1}"),
        @CompoundIndex(name = "idx_items_category_hs", def = "{'items.category': 1, 'items.hsCode': 1}")
})
public class ParcelManifestDocument {

    @Id
    private String id; // MongoDB ObjectId або строковий ідентифікатор

    private Long sqlParcelId; // ID у таблиці parcels (для реляційного мапінгу)

    @Indexed(unique = true)
    private String trackingNumber;

    private Long senderId;
    private Long receiverId;
    private BigDecimal weight;
    private Long statusId;
    private boolean deleted;
    private LocalDateTime lastModifiedAt;

    // 1:1 Вбудована специфікація посилки
    private ParcelSpecification specification;

    // 1:1 Вбудована митна декларація
    private CustomsDeclaration customsDeclaration;

    // 1:N Масив вкладень (товарів у посилці)
    private List<ParcelItem> items;

    // 1:N Масив подій трекінгу та IoT-телеметрії
    private List<TrackingTelemetryEvent> telemetryEvents;

    public ParcelManifestDocument() {
    }

    public ParcelManifestDocument(
            String id,
            Long sqlParcelId,
            String trackingNumber,
            Long senderId,
            Long receiverId,
            BigDecimal weight,
            Long statusId,
            boolean deleted,
            LocalDateTime lastModifiedAt,
            ParcelSpecification specification,
            CustomsDeclaration customsDeclaration,
            List<ParcelItem> items,
            List<TrackingTelemetryEvent> telemetryEvents
    ) {
        this.id = id;
        this.sqlParcelId = sqlParcelId;
        this.trackingNumber = trackingNumber;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.weight = weight;
        this.statusId = statusId;
        this.deleted = deleted;
        this.lastModifiedAt = lastModifiedAt;
        this.specification = specification;
        this.customsDeclaration = customsDeclaration;
        this.items = items;
        this.telemetryEvents = telemetryEvents;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getSqlParcelId() {
        return sqlParcelId;
    }

    public void setSqlParcelId(Long sqlParcelId) {
        this.sqlParcelId = sqlParcelId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public Long getStatusId() {
        return statusId;
    }

    public void setStatusId(Long statusId) {
        this.statusId = statusId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(LocalDateTime lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public ParcelSpecification getSpecification() {
        return specification;
    }

    public void setSpecification(ParcelSpecification specification) {
        this.specification = specification;
    }

    public CustomsDeclaration getCustomsDeclaration() {
        return customsDeclaration;
    }

    public void setCustomsDeclaration(CustomsDeclaration customsDeclaration) {
        this.customsDeclaration = customsDeclaration;
    }

    public List<ParcelItem> getItems() {
        return items;
    }

    public void setItems(List<ParcelItem> items) {
        this.items = items;
    }

    public List<TrackingTelemetryEvent> getTelemetryEvents() {
        return telemetryEvents;
    }

    public void setTelemetryEvents(List<TrackingTelemetryEvent> telemetryEvents) {
        this.telemetryEvents = telemetryEvents;
    }
}
