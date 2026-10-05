package com.ridehailing.userservice.repository;

import com.ridehailing.userservice.model.Vehicle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class VehicleRepository {

    private final JdbcClient jdbcClient;

    public VehicleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Vehicle> findByDriverId(Long driverId) {
        return jdbcClient.sql("SELECT id, driver_id, plate, type, model FROM vehicles WHERE driver_id = ?")
                .param(driverId)
                .query(Vehicle.class)
                .optional();
    }

    public Optional<Vehicle> findByPlate(String plate) {
        return jdbcClient.sql("SELECT id, driver_id, plate, type, model FROM vehicles WHERE plate = ?")
                .param(plate)
                .query(Vehicle.class)
                .optional();
    }

    public void insert(Long driverId, String plate, String type, String model) {
        jdbcClient.sql("INSERT INTO vehicles (driver_id, plate, type, model) VALUES (?, ?, ?, ?)")
                .params(driverId, plate, type, model)
                .update();
    }

    public int update(Long driverId, String plate, String type, String model) {
        return jdbcClient.sql("UPDATE vehicles SET plate = ?, type = ?, model = ? WHERE driver_id = ?")
                .params(plate, type, model, driverId)
                .update();
    }
}