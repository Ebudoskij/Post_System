package com.ebudosky.post_system.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TrackingHistoryRepository {
    private final JdbcClient jdbcClient;

    public TrackingHistoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Use STORED PROCEDURE for writing
    public void addHistory(Long parcelId, Long statusId, Long branchId) {
        jdbcClient.sql("CALL sp_add_tracking_history(:parcelId, :statusId, :branchId)")
                .param("parcelId", parcelId)
                .param("statusId", statusId)
                .param("branchId", branchId)
                .update();
    }
}