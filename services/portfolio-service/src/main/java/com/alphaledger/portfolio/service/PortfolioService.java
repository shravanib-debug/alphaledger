package com.alphaledger.portfolio.service;

import java.time.Clock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.common.web.PageResponse;
import com.alphaledger.portfolio.domain.Fund;
import com.alphaledger.portfolio.domain.Portfolio;
import com.alphaledger.portfolio.domain.PortfolioStatus;
import com.alphaledger.portfolio.dto.FundRequest;
import com.alphaledger.portfolio.dto.PortfolioRequest;
import com.alphaledger.portfolio.dto.PortfolioResponse;
import com.alphaledger.portfolio.repository.PortfolioRepository;

/**
 * Portfolio rules (prd.md §3): every role can view; an Admin manages any portfolio; a PM creates portfolios
 * for themselves and changes only the portfolios they manage. Archived portfolios are read-only.
 */
@Service
public class PortfolioService {

    private final PortfolioRepository portfolios;
    private final Clock clock;

    public PortfolioService(PortfolioRepository portfolios, Clock clock) {
        this.portfolios = portfolios;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PortfolioResponse> list(PortfolioStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id"));
        Page<Portfolio> result = status == null ? portfolios.findAll(pageable) : portfolios.findByStatus(status, pageable);
        return PageResponse.of(result.map(PortfolioResponse::from).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PortfolioResponse get(long id) {
        return PortfolioResponse.from(find(id));
    }

    @Transactional
    public PortfolioResponse create(PortfolioRequest request, AuthenticatedUser user) {
        long managerId = resolveManager(request.managerId(), user);
        String name = request.name().trim();
        if (portfolios.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A portfolio named '" + name + "' already exists");
        }
        Portfolio portfolio = new Portfolio(name, managerId, request.baseCurrencyOrDefault(), clock.instant());
        return PortfolioResponse.from(portfolios.save(portfolio));
    }

    @Transactional
    public PortfolioResponse update(long id, PortfolioRequest request, AuthenticatedUser user) {
        Portfolio portfolio = findEditable(id, user);
        String name = request.name().trim();
        if (portfolios.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A portfolio named '" + name + "' already exists");
        }
        portfolio.rename(name, clock.instant());
        if (request.managerId() != null && !request.managerId().equals(portfolio.getManagerId())) {
            if (!user.isAdmin()) {
                throw new AccessDeniedException("Only an admin can reassign a portfolio");
            }
            portfolio.reassign(request.managerId(), clock.instant());
        }
        return PortfolioResponse.from(portfolio);
    }

    @Transactional
    public PortfolioResponse archive(long id, AuthenticatedUser user) {
        Portfolio portfolio = find(id);
        checkCanManage(portfolio, user);
        if (!portfolio.isArchived()) {
            portfolio.archive(clock.instant());
        }
        return PortfolioResponse.from(portfolio);
    }

    @Transactional
    public PortfolioResponse.FundResponse addFund(long portfolioId, FundRequest request, AuthenticatedUser user) {
        Portfolio portfolio = findEditable(portfolioId, user);
        String name = request.name().trim();
        if (portfolio.hasFundNamed(name)) {
            throw new ConflictException("Portfolio " + portfolioId + " already has a fund named '" + name + "'");
        }
        Fund fund = portfolio.addFund(name, request.assetClass(), clock.instant());
        portfolios.flush(); // assigns the fund ID
        return PortfolioResponse.FundResponse.from(fund);
    }

    private long resolveManager(Long requestedManagerId, AuthenticatedUser user) {
        if (user.isAdmin()) {
            if (requestedManagerId == null) {
                throw new ValidationException("managerId: is required when an admin creates a portfolio");
            }
            return requestedManagerId;
        }
        if (requestedManagerId != null && requestedManagerId != user.id()) {
            throw new AccessDeniedException("A PM can only create portfolios they manage");
        }
        return user.id();
    }

    private Portfolio findEditable(long id, AuthenticatedUser user) {
        Portfolio portfolio = find(id);
        checkCanManage(portfolio, user);
        if (portfolio.isArchived()) {
            throw new BusinessRuleException("Portfolio " + id + " is archived and cannot be changed");
        }
        return portfolio;
    }

    private static void checkCanManage(Portfolio portfolio, AuthenticatedUser user) {
        if (!user.isAdmin() && !portfolio.isManagedBy(user.id())) {
            throw new AccessDeniedException("Portfolio " + portfolio.getId() + " is managed by another user");
        }
    }

    private Portfolio find(long id) {
        return portfolios.findById(id).orElseThrow(() -> new NotFoundException("Portfolio " + id + " not found"));
    }
}
