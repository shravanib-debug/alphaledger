package com.alphaledger.portfolio.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "portfolio")
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortfolioStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Fund> funds = new ArrayList<>();

    protected Portfolio() {
    }

    public Portfolio(String name, Long managerId, String baseCurrency, Instant now) {
        this.name = name;
        this.managerId = managerId;
        this.baseCurrency = baseCurrency;
        this.status = PortfolioStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isManagedBy(long userId) {
        return managerId == userId;
    }

    public boolean isArchived() {
        return status == PortfolioStatus.ARCHIVED;
    }

    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    public void reassign(Long managerId, Instant now) {
        this.managerId = managerId;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.status = PortfolioStatus.ARCHIVED;
        this.updatedAt = now;
    }

    public Fund addFund(String name, AssetClass assetClass, Instant now) {
        Fund fund = new Fund(this, name, assetClass);
        funds.add(fund);
        this.updatedAt = now;
        return fund;
    }

    public boolean hasFundNamed(String name) {
        return funds.stream().anyMatch(fund -> fund.getName().equalsIgnoreCase(name));
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getManagerId() {
        return managerId;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public PortfolioStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<Fund> getFunds() {
        return funds;
    }
}
