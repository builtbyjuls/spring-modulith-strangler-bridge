package com.builtbyjuls.grid.contract.internal;

import java.util.Objects;

import com.builtbyjuls.grid.contract.ParticipantRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Generators (sellers) and distribution utilities (buyers) operating on the grid.
 */
@Entity
@Table(name = "market_participant")
public class MarketParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_code", nullable = false, unique = true, length = 32)
    private String participantCode;

    @Column(name = "short_name", nullable = false, length = 128)
    private String shortName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ParticipantRole role;

    protected MarketParticipant() {
    }

    public MarketParticipant(String participantCode, String shortName, ParticipantRole role) {
        this.participantCode = participantCode;
        this.shortName = shortName;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getParticipantCode() {
        return participantCode;
    }

    public void setParticipantCode(String participantCode) {
        this.participantCode = participantCode;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public ParticipantRole getRole() {
        return role;
    }

    public void setRole(ParticipantRole role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MarketParticipant that)) return false;
        return Objects.equals(participantCode, that.participantCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(participantCode);
    }

    @Override
    public String toString() {
        return "MarketParticipant{" +
                "id=" + id +
                ", participantCode='" + participantCode + '\'' +
                ", shortName='" + shortName + '\'' +
                ", role=" + role +
                '}';
    }
}
