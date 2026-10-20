package com.builtbyjuls.grid.metering.internal;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 5-minute interval telemetry reading for wholesale energy reconciliation.
 */
@Entity
@Table(name = "meter_interval_reading")
public class MeterIntervalReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interval_index", nullable = false)
    private Integer intervalIndex;

    @Column(name = "actual_mwh", nullable = false, precision = 18, scale = 6)
    private BigDecimal actualMwh;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "header_id", nullable = false)
    private MeterReadingHeader header;

    protected MeterIntervalReading() {
    }

    public MeterIntervalReading(Integer intervalIndex, BigDecimal actualMwh) {
        this.intervalIndex = intervalIndex;
        this.actualMwh = actualMwh;
    }

    public MeterIntervalReading(Integer intervalIndex, BigDecimal actualMwh, MeterReadingHeader header) {
        this.intervalIndex = intervalIndex;
        this.actualMwh = actualMwh;
        this.header = header;
    }

    public Long getId() {
        return id;
    }

    public Integer getIntervalIndex() {
        return intervalIndex;
    }

    public void setIntervalIndex(Integer intervalIndex) {
        this.intervalIndex = intervalIndex;
    }

    public BigDecimal getActualMwh() {
        return actualMwh;
    }

    public void setActualMwh(BigDecimal actualMwh) {
        this.actualMwh = actualMwh;
    }

    public MeterReadingHeader getHeader() {
        return header;
    }

    public void setHeader(MeterReadingHeader header) {
        this.header = header;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeterIntervalReading that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
