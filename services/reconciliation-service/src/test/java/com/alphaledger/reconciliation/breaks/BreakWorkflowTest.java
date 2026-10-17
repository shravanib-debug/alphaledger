package com.alphaledger.reconciliation.breaks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.alphaledger.common.error.BusinessRuleException;
import com.alphaledger.common.error.ValidationException;
import com.alphaledger.reconciliation.breaks.BreakWorkflow.Action;
import com.alphaledger.reconciliation.breaks.BreakWorkflow.BreakStatus;

/** Break lifecycle (FR-REC-7, FR-REC-8, prd.md business rule 7): one-directional, every action needs a comment. */
class BreakWorkflowTest {

    @Test
    void assignMovesAnOpenBreakIntoReview() {
        assertThat(BreakWorkflow.next(BreakStatus.OPEN, Action.ASSIGN, "Taking this")).isEqualTo(BreakStatus.IN_REVIEW);
        assertThat(BreakWorkflow.next(BreakStatus.IN_REVIEW, Action.ASSIGN, "Handing to Rohan"))
                .isEqualTo(BreakStatus.IN_REVIEW);
    }

    @Test
    void resolutionsCloseABreakUnderReview() {
        for (Action resolution : new Action[] {Action.ACCEPT_EXTERNAL, Action.ADJUST_INTERNAL, Action.KNOWN_DIFFERENCE}) {
            assertThat(BreakWorkflow.next(BreakStatus.IN_REVIEW, resolution, "Checked with custodian"))
                    .isEqualTo(BreakStatus.RESOLVED);
        }
        assertThat(BreakWorkflow.next(BreakStatus.IN_REVIEW, Action.ESCALATE, "Needs desk head"))
                .isEqualTo(BreakStatus.ESCALATED);
    }

    @Test
    void anEscalatedBreakCanStillBeResolved() {
        assertThat(BreakWorkflow.next(BreakStatus.ESCALATED, Action.KNOWN_DIFFERENCE, "Desk head approved"))
                .isEqualTo(BreakStatus.RESOLVED);
    }

    @Test
    void breaksMustBeReviewedBeforeResolutionAndCannotBeReopened() {
        assertThatThrownBy(() -> BreakWorkflow.next(BreakStatus.OPEN, Action.ACCEPT_EXTERNAL, "x"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("A break in status OPEN cannot be ACCEPT_EXTERNAL");
        assertThatThrownBy(() -> BreakWorkflow.next(BreakStatus.RESOLVED, Action.ASSIGN, "reopen"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BreakWorkflow.next(BreakStatus.ESCALATED, Action.ESCALATE, "again"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void everyActionNeedsAComment() {
        assertThatThrownBy(() -> BreakWorkflow.next(BreakStatus.IN_REVIEW, Action.ADJUST_INTERNAL, "  "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("comment: is required for every break action");
    }

    @Test
    void resolutionTypeIsRecordedOnlyForResolutions() {
        assertThat(Action.ACCEPT_EXTERNAL.isResolution()).isTrue();
        assertThat(Action.ESCALATE.isResolution()).isFalse();
        assertThat(Action.ASSIGN.isResolution()).isFalse();
    }
}
