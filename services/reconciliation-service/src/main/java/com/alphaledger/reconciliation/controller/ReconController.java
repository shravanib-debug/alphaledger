package com.alphaledger.reconciliation.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.alphaledger.common.error.ValidationException;
import com.alphaledger.common.security.AuthenticatedUser;
import com.alphaledger.reconciliation.breaks.BreakService;
import com.alphaledger.reconciliation.breaks.BreakWorkflow.Action;
import com.alphaledger.reconciliation.run.ReconRunService;

/**
 * Reconciliation runs and breaks (prd.md §4.6). Admin and Ops Analysts upload files and work breaks; Auditors read.
 * Portfolio managers have no access to reconciliation (prd.md §3, role permissions).
 */
@RestController
@RequestMapping("/api/v1/recon")
@PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST', 'AUDITOR')")
public class ReconController {

    private final ReconRunService runs;
    private final BreakService breaks;

    public ReconController(ReconRunService runs, BreakService breaks) {
        this.runs = runs;
        this.breaks = breaks;
    }

    /** 201 with the run summary; 200 with the existing run when the same file was already reconciled for the date. */
    @PostMapping(path = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public ResponseEntity<Map<String, Object>> startRun(
            @RequestPart("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate runDate,
            @RequestParam(defaultValue = "DEMO-CUSTODIAN") @Size(max = 40) String source,
            Authentication authentication) throws IOException {
        if (file.isEmpty()) {
            throw new ValidationException("file: is empty");
        }
        ReconRunService.RunOutcome outcome = runs.start(file, file.getSize(), file.getOriginalFilename(), runDate,
                source.trim(), AuthenticatedUser.from(authentication).id());
        return ResponseEntity.status(outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.run());
    }

    /** Resumes a FAILED run from its last committed work; 409 for any other status. */
    @PostMapping("/runs/{id}/restart")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> restartRun(@PathVariable long id) {
        return runs.restart(id);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return runs.runs(limit);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable long id) {
        return runs.summary(id);
    }

    @GetMapping("/runs/{id}/errors")
    public List<Map<String, Object>> runErrors(@PathVariable long id) {
        return runs.errors(id);
    }

    @GetMapping("/breaks")
    public List<Map<String, Object>> breaks(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) Long runId,
                                            @RequestParam(required = false) Long assignedTo,
                                            @RequestParam(required = false) @Min(0) Integer minAgeDays,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return breaks.list(status, type, runId, assignedTo, minAgeDays, page, size);
    }

    @GetMapping("/breaks/{id}")
    public Map<String, Object> breakDetail(@PathVariable long id) {
        return breaks.get(id);
    }

    @GetMapping("/breaks/{id}/audit")
    public List<Map<String, Object>> audit(@PathVariable long id) {
        return breaks.audit(id);
    }

    public record AssignRequest(Long assigneeId, @NotBlank @Size(max = 500) String comment) {
    }

    public record ResolveRequest(@NotNull Action action, @NotBlank @Size(max = 500) String comment) {
    }

    public record CommentRequest(@NotBlank @Size(max = 500) String comment) {
    }

    @PostMapping("/breaks/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> assign(@PathVariable long id, @Valid @RequestBody AssignRequest request,
                                      Authentication authentication) {
        return breaks.act(id, Action.ASSIGN, request.assigneeId(), request.comment(),
                AuthenticatedUser.from(authentication));
    }

    /** {@code action}: ACCEPT_EXTERNAL, ADJUST_INTERNAL or KNOWN_DIFFERENCE (FR-REC-8). */
    @PostMapping("/breaks/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> resolve(@PathVariable long id, @Valid @RequestBody ResolveRequest request,
                                       Authentication authentication) {
        if (!request.action().isResolution()) {
            throw new ValidationException("action: must be ACCEPT_EXTERNAL, ADJUST_INTERNAL or KNOWN_DIFFERENCE");
        }
        return breaks.act(id, request.action(), null, request.comment(), AuthenticatedUser.from(authentication));
    }

    @PostMapping("/breaks/{id}/escalate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS_ANALYST')")
    public Map<String, Object> escalate(@PathVariable long id, @Valid @RequestBody CommentRequest request,
                                        Authentication authentication) {
        return breaks.act(id, Action.ESCALATE, null, request.comment(), AuthenticatedUser.from(authentication));
    }
}
