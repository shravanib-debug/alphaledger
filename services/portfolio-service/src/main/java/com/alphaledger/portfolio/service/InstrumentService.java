package com.alphaledger.portfolio.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alphaledger.common.error.ConflictException;
import com.alphaledger.common.error.NotFoundException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.instrument.Isin;
import com.alphaledger.common.web.PageResponse;
import com.alphaledger.portfolio.domain.AssetClass;
import com.alphaledger.portfolio.domain.AssetClassProxy;
import com.alphaledger.portfolio.domain.Instrument;
import com.alphaledger.portfolio.domain.InstrumentType;
import com.alphaledger.portfolio.dto.CreateInstrumentRequest;
import com.alphaledger.portfolio.dto.InstrumentResponse;
import com.alphaledger.portfolio.dto.UpdateInstrumentRequest;
import com.alphaledger.portfolio.repository.InstrumentRepository;

@Service
public class InstrumentService {

    private final InstrumentRepository instruments;

    public InstrumentService(InstrumentRepository instruments) {
        this.instruments = instruments;
    }

    @Transactional(readOnly = true)
    public PageResponse<InstrumentResponse> search(AssetClass assetClass, InstrumentType type, String isin,
                                                   String text, int page, int size) {
        Page<Instrument> result = instruments.findAll(InstrumentRepository.matching(assetClass, type, isin, text),
                PageRequest.of(page, size, Sort.by("id")));
        return PageResponse.of(result.map(InstrumentResponse::from).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public InstrumentResponse get(long id) {
        return InstrumentResponse.from(find(id));
    }

    @Transactional
    public InstrumentResponse create(CreateInstrumentRequest request) {
        String isin = Isin.normalize(request.isin());
        validateIdentity(isin, request.synthetic(), request.instrumentType(), request.assetClassProxy());
        if (instruments.existsByIsin(isin)) {
            throw new ConflictException("An instrument with ISIN " + isin + " already exists");
        }
        Instrument instrument = new Instrument(isin, blankToNull(request.symbol()), blankToNull(request.exchange()),
                request.name().trim(), request.assetClass(), request.assetClassProxy(), request.instrumentType(),
                blankToNull(request.amfiSchemeCode()), request.synthetic());
        return InstrumentResponse.from(instruments.save(instrument));
    }

    @Transactional
    public InstrumentResponse update(long id, UpdateInstrumentRequest request) {
        Instrument instrument = find(id);
        instrument.update(blankToNull(request.symbol()), blankToNull(request.exchange()), request.name().trim(),
                blankToNull(request.amfiSchemeCode()), request.status());
        return InstrumentResponse.from(instrument);
    }

    /**
     * Real instruments need a valid ISIN (format and check digit); synthetic ones use the reserved ZZSYN prefix,
     * the PRIVATE type and the SYNTHETIC_PRIVATE proxy, so they can never be mistaken for real securities.
     */
    public static void validateIdentity(String isin, boolean synthetic, InstrumentType type, AssetClassProxy proxy) {
        if (!Isin.hasValidFormat(isin)) {
            throw new ValidationException("isin: must be 2 letters, 9 letters or digits, and a check digit");
        }
        if (synthetic) {
            if (!Isin.isSynthetic(isin) || type != InstrumentType.PRIVATE || proxy != AssetClassProxy.SYNTHETIC_PRIVATE) {
                throw new ValidationException(
                        "synthetic instruments need a " + Isin.SYNTHETIC_PREFIX + " identifier, type PRIVATE "
                                + "and proxy SYNTHETIC_PRIVATE");
            }
            return;
        }
        if (Isin.isSynthetic(isin) || type == InstrumentType.PRIVATE || proxy == AssetClassProxy.SYNTHETIC_PRIVATE) {
            throw new ValidationException("only synthetic instruments may use the ZZSYN prefix, PRIVATE type or "
                    + "SYNTHETIC_PRIVATE proxy");
        }
        if (!Isin.isValid(isin)) {
            throw new ValidationException("isin: check digit is wrong for " + isin);
        }
    }

    private Instrument find(long id) {
        return instruments.findById(id).orElseThrow(() -> new NotFoundException("Instrument " + id + " not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
