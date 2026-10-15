package com.alphaledger.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.Portfolio;
import com.alphaledger.portfolio.dto.FundRequest;
import com.alphaledger.portfolio.dto.PortfolioRequest;
import com.alphaledger.portfolio.dto.PortfolioResponse;
import com.alphaledger.portfolio.repository.PortfolioRepository;

class PortfolioServiceTest {

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1, "anita@x", Set.of("ADMIN"));
    private static final AuthenticatedUser PRIYA = new AuthenticatedUser(2, "priya@x", Set.of("PM"));
    private static final AuthenticatedUser OTHER_PM = new AuthenticatedUser(9, "other@x", Set.of("PM"));

    private final PortfolioRepository repository = mock(PortfolioRepository.class);
    private final PortfolioService service = new PortfolioService(repository,
            Clock.fixed(Instant.parse("2026-10-15T09:00:00Z"), ZoneOffset.UTC));

    @Test
    void pmCreatesPortfoliosForThemselvesInInr() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PortfolioResponse created = service.create(new PortfolioRequest("New Fund", null, null), PRIYA);

        assertThat(created.managerId()).isEqualTo(2L);
        assertThat(created.baseCurrency()).isEqualTo("INR");
    }

    @Test
    void pmCannotCreateForSomeoneElseAndAdminMustNameAManager() {
        assertThatThrownBy(() -> service.create(new PortfolioRequest("X", 9L, null), PRIYA))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(new PortfolioRequest("X", null, null), ADMIN))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void duplicateNamesAreRejected() {
        when(repository.existsByNameIgnoreCase("Taken")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new PortfolioRequest("Taken", null, null), PRIYA))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheManagingPmOrAnAdminCanChangeAPortfolio() {
        Portfolio priyas = portfolio(7L, 2L);
        when(repository.findById(7L)).thenReturn(Optional.of(priyas));

        assertThatThrownBy(() -> service.update(7L, new PortfolioRequest("Renamed", null, null), OTHER_PM))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.update(7L, new PortfolioRequest("Renamed", null, null), PRIYA).name()).isEqualTo("Renamed");
        assertThat(service.update(7L, new PortfolioRequest("Renamed", 9L, null), ADMIN).managerId()).isEqualTo(9L);
    }

    @Test
    void pmCannotReassignTheirPortfolio() {
        when(repository.findById(7L)).thenReturn(Optional.of(portfolio(7L, 2L)));

        assertThatThrownBy(() -> service.update(7L, new PortfolioRequest("Same", 9L, null), PRIYA))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void archivedPortfoliosAreReadOnly() {
        Portfolio archived = portfolio(7L, 2L);
        archived.archive(Instant.now());
        when(repository.findById(7L)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.update(7L, new PortfolioRequest("New name", null, null), PRIYA))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.addFund(7L, new FundRequest("Sleeve", AssetClass.CREDIT), PRIYA))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void fundNamesAreUniqueWithinAPortfolio() {
        Portfolio priyas = portfolio(7L, 2L);
        priyas.addFund("REIT Sleeve", AssetClass.REAL_ESTATE, Instant.now());
        when(repository.findById(7L)).thenReturn(Optional.of(priyas));

        assertThatThrownBy(() -> service.addFund(7L, new FundRequest("reit sleeve", AssetClass.REAL_ESTATE), PRIYA))
                .isInstanceOf(ConflictException.class);
    }

    private static Portfolio portfolio(long id, long managerId) {
        Portfolio portfolio = new Portfolio("Existing", managerId, "INR", Instant.now());
        ReflectionTestUtils.setField(portfolio, "id", id);
        return portfolio;
    }
}
