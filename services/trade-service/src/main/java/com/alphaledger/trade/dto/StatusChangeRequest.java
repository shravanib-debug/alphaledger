package com.alphaledger.trade.dto;

import jakarta.validation.constraints.Size;

/** Optional comment recorded in the trade audit for a cancel or manual settle. */
public record StatusChangeRequest(@Size(max = 500) String comment) {
}
