package com.builtbyjuls.grid.contract.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.builtbyjuls.grid.contract.DeclarationStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * Daily bilateral contract declaration header submitted for wholesale settlement.
 */
@Entity
@Table(name = "contract_declaration")
public class ContractDeclaration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeclarationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bilateral_contract_id", nullable = false)
    private BilateralContract bilateralContract;

    @OneToMany(mappedBy = "declaration", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<IntervalQuantity> intervals = new ArrayList<>();

    protected ContractDeclaration() {
    }

    public ContractDeclaration(LocalDate tradingDate, DeclarationStatus status, BilateralContract bilateralContract) {
        this.tradingDate = tradingDate;
        this.status = status;
        this.bilateralContract = bilateralContract;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTradingDate() {
        return tradingDate;
    }

    public void setTradingDate(LocalDate tradingDate) {
        this.tradingDate = tradingDate;
    }

    public DeclarationStatus getStatus() {
        return status;
    }

    public void setStatus(DeclarationStatus status) {
        this.status = status;
    }

    public BilateralContract getBilateralContract() {
        return bilateralContract;
    }

    public void setBilateralContract(BilateralContract bilateralContract) {
        this.bilateralContract = bilateralContract;
    }

    public List<IntervalQuantity> getIntervals() {
        return intervals;
    }

    public void addInterval(IntervalQuantity interval) {
        intervals.add(interval);
        interval.setDeclaration(this);
    }

    public void removeInterval(IntervalQuantity interval) {
        intervals.remove(interval);
        interval.setDeclaration(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContractDeclaration that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ContractDeclaration{" +
                "id=" + id +
                ", tradingDate=" + tradingDate +
                ", status=" + status +
                '}';
    }
}
