package com.builtbyjuls.grid.contract.internal;

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
 * Bilateral power supply contracts between a seller generator and buyer utility.
 */
@Entity
@Table(name = "bilateral_contract")
public class BilateralContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_code", nullable = false, unique = true, length = 64)
    private String contractCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private MarketParticipant seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private MarketParticipant buyer;

    protected BilateralContract() {
    }

    public BilateralContract(String contractCode, MarketParticipant seller, MarketParticipant buyer) {
        this.contractCode = contractCode;
        this.seller = seller;
        this.buyer = buyer;
    }

    public Long getId() {
        return id;
    }

    public String getContractCode() {
        return contractCode;
    }

    public void setContractCode(String contractCode) {
        this.contractCode = contractCode;
    }

    public MarketParticipant getSeller() {
        return seller;
    }

    public void setSeller(MarketParticipant seller) {
        this.seller = seller;
    }

    public MarketParticipant getBuyer() {
        return buyer;
    }

    public void setBuyer(MarketParticipant buyer) {
        this.buyer = buyer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BilateralContract that)) return false;
        return Objects.equals(contractCode, that.contractCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contractCode);
    }

    @Override
    public String toString() {
        return "BilateralContract{" +
                "id=" + id +
                ", contractCode='" + contractCode + '\'' +
                '}';
    }
}
