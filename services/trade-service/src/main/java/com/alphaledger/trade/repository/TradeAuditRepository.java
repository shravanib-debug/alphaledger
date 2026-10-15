package com.alphaledger.trade.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.alphaledger.trade.domain.TradeAudit;

public interface TradeAuditRepository extends JpaRepository<TradeAudit, Long> {

    List<TradeAudit> findByTradeIdOrderByIdAsc(Long tradeId);
}
