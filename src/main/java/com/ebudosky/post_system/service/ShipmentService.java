package com.ebudosky.post_system.service;

import com.ebudosky.post_system.repository.ParcelRepository;
import com.ebudosky.post_system.repository.TrackingHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class ShipmentService {

    private final ParcelRepository parcelRepository;
    private final TrackingHistoryRepository trackingHistoryRepository;

    public ShipmentService(ParcelRepository parcelRepository, TrackingHistoryRepository trackingHistoryRepository) {
        this.parcelRepository = parcelRepository;
        this.trackingHistoryRepository = trackingHistoryRepository;
    }

    /**
     * This method acts as a Unit of Work.
     * It opens a transaction, and if either parcel registration or recording
     * to history fails, the transaction will ROLLBACK. All operations are performed
     * via stored procedures.
     */
    @Transactional
    public void registerNewShipment(String trackingNumber, Long senderId, Long receiverId, BigDecimal weight, Long initialBranchId) {
        Long statusCreatedId = 1L; // Status "Created"

        // 1. Register the parcel via stored procedure
        Long newParcelId = parcelRepository.createParcel(
                trackingNumber, senderId, receiverId, weight, statusCreatedId
        );

        // 2. Add tracking record (also via stored procedure)
        trackingHistoryRepository.addHistory(newParcelId, statusCreatedId, initialBranchId);

        // The transaction will successfully commit both operations.
    }
}
