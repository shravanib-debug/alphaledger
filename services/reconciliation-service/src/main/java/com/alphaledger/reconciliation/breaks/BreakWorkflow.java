package com.alphaledger.reconciliation.breaks;

import java.util.EnumSet;
import java.util.Set;

import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.ValidationException;

/**
 * Break lifecycle (prd.md business rule 7): {@code OPEN -> IN_REVIEW -> RESOLVED | ESCALATED}, one-directional.
 * Assigning puts a break into review; the three resolutions (FR-REC-8) close it; escalating hands it upwards, after
 * which it can still be resolved. A resolved break is never reopened (a new break is raised instead). Every action
 * needs a comment.
 */
public final class BreakWorkflow {

    public enum BreakStatus { OPEN, IN_REVIEW, RESOLVED, ESCALATED }

    public enum Action {
        ASSIGN, ESCALATE, ACCEPT_EXTERNAL, ADJUST_INTERNAL, KNOWN_DIFFERENCE;

        public boolean isResolution() {
            return this == ACCEPT_EXTERNAL || this == ADJUST_INTERNAL || this == KNOWN_DIFFERENCE;
        }
    }

    private static final Set<BreakStatus> RESOLVABLE = EnumSet.of(BreakStatus.IN_REVIEW, BreakStatus.ESCALATED);

    private BreakWorkflow() {
    }

    public static BreakStatus next(BreakStatus current, Action action, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ValidationException("comment: is required for every break action");
        }
        BreakStatus next = switch (action) {
            case ASSIGN -> current == BreakStatus.OPEN || current == BreakStatus.IN_REVIEW ? BreakStatus.IN_REVIEW : null;
            case ESCALATE -> current == BreakStatus.IN_REVIEW ? BreakStatus.ESCALATED : null;
            case ACCEPT_EXTERNAL, ADJUST_INTERNAL, KNOWN_DIFFERENCE -> RESOLVABLE.contains(current)
                    ? BreakStatus.RESOLVED : null;
        };
        if (next == null) {
            throw new BusinessRuleException("A break in status " + current + " cannot be " + action);
        }
        return next;
    }
}
