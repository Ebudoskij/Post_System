package com.ebudosky.post_system.repository;

import com.ebudosky.post_system.model.Parcel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;

@Repository
public class ParcelRepository {

    private final JdbcClient jdbcClient;

    public ParcelRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Use VIEW for reading
    public List<Parcel> findAllActive() {
        return jdbcClient.sql("SELECT id, tracking_number, sender_id, receiver_id, weight, status_id FROM v_active_parcels")
                .query(Parcel.class)
                .list();
    }

    // Use STORED PROCEDURE for writing
    public Long createParcel(String trackingNumber, Long senderId, Long receiverId, BigDecimal weight, Long statusId) {
        // In PostgreSQL, OUT parameters in procedures can be conveniently retrieved via a regular SELECT from the procedure
        // or via CallableStatement; JdbcClient provides great support for CALL invocations
        return jdbcClient.sql("CALL sp_create_parcel(:trackingNum, :sender, :receiver, :weight, :status, NULL)")
                .param("trackingNum", trackingNumber)
                .param("sender", senderId)
                .param("receiver", receiverId)
                .param("weight", weight)
                .param("status", statusId)
                .query(Long.class)
                .single();
    }
}
