package com.builtbyjuls.grid.contract.internal;

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
import jakarta.persistence.UniqueConstraint;

/**
 * 5-minute dispatch interval schedule with declared MWh.
 * 288 intervals represent a single 24-hour trading day.
 */
@Entity
@Table(
        name = "interval_quantity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_interval_decl_node",
                columnNames = {"declaration_id", "dispatch_interval", "grid_node_code"}
        )
)
public class IntervalQuantity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispatch_interval", nullable = false)
    private int dispatchInterval;

    @Column(name = "grid_node_code", nullable = false, length = 32)
    private String gridNodeCode;

    @Column(name = "declared_mwh", nullable = false, precision = 18, scale = 6)
    private BigDecimal declaredMwh;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declaration_id", nullable = false)
    private ContractDeclaration declaration;

    protected IntervalQuantity() {
    }

    public IntervalQuantity(int dispatchInterval, String gridNodeCode, BigDecimal declaredMwh, ContractDeclaration declaration) {
        this.dispatchInterval = dispatchInterval;
        this.gridNodeCode = gridNodeCode;
        this.declaredMwh = declaredMwh;
        this.declaration = declaration;
    }

    public Long getId() {
        return id;
    }

    public int getDispatchInterval() {
        return dispatchInterval;
    }

    public void setDispatchInterval(int dispatchInterval) {
        this.dispatchInterval = dispatchInterval;
    }

    public String getGridNodeCode() {
        return gridNodeCode;
    }

    public void setGridNodeCode(String gridNodeCode) {
        this.gridNodeCode = gridNodeCode;
    }

    public BigDecimal getDeclaredMwh() {
        return declaredMwh;
    }

    public void setDeclaredMwh(BigDecimal declaredMwh) {
        this.declaredMwh = declaredMwh;
    }

    public ContractDeclaration getDeclaration() {
        return declaration;
    }

    public void setDeclaration(ContractDeclaration declaration) {
        this.declaration = declaration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntervalQuantity that)) return false;
        return dispatchInterval == that.dispatchInterval
                && Objects.equals(gridNodeCode, that.gridNodeCode)
                && Objects.equals(declaration != null ? declaration.getId() : null,
                                  that.declaration != null ? that.declaration.getId() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dispatchInterval, gridNodeCode, declaration != null ? declaration.getId() : null);
    }

    @Override
    public String toString() {
        return "IntervalQuantity{" +
                "id=" + id +
                ", dispatchInterval=" + dispatchInterval +
                ", gridNodeCode='" + gridNodeCode + '\'' +
                ", declaredMwh=" + declaredMwh +
                '}';
    }
}
