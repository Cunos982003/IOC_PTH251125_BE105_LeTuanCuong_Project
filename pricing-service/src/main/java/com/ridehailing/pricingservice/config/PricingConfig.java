package com.ridehailing.pricingservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "pricing")
public class PricingConfig {
    private long baseFare;
    private long perKm;
    private long perMinute;
    private long minFare;
    private double distanceMultiplier;
    private double avgSpeedKmh;

    public long getBaseFare() { return baseFare; }
    public void setBaseFare(long baseFare) { this.baseFare = baseFare; }

    public long getPerKm() { return perKm; }
    public void setPerKm(long perKm) { this.perKm = perKm; }

    public long getPerMinute() { return perMinute; }
    public void setPerMinute(long perMinute) { this.perMinute = perMinute; }

    public long getMinFare() { return minFare; }
    public void setMinFare(long minFare) { this.minFare = minFare; }

    public double getDistanceMultiplier() { return distanceMultiplier; }
    public void setDistanceMultiplier(double distanceMultiplier) { this.distanceMultiplier = distanceMultiplier; }

    public double getAvgSpeedKmh() { return avgSpeedKmh; }
    public void setAvgSpeedKmh(double avgSpeedKmh) { this.avgSpeedKmh = avgSpeedKmh; }
}
