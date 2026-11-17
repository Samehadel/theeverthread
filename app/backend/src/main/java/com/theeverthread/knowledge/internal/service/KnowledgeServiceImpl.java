package com.theeverthread.knowledge.internal.service;

import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.knowledge.api.dto.*;
import com.theeverthread.knowledge.api.exception.KnowledgeException;
import com.theeverthread.knowledge.api.service.KnowledgeService;
import com.theeverthread.knowledge.internal.entity.TicketEntity;
import com.theeverthread.knowledge.internal.mapper.TicketMapper;
import com.theeverthread.knowledge.internal.repository.TicketRepository;
import com.theeverthread.knowledge.internal.repository.TicketSummaryRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {
    private final IdentityService identity;
    private final TicketRepository tickets;
    private final TicketMapper mapper;
    private final TicketSummaryRepository summaries;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final KnowledgeAi ai;
    private final AttributeCatalog catalog;

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> attributes(String email, Long workspaceId) {
        identity.current(email, workspaceId);
        return catalog.labels();
    }

    @Override
    @Transactional
    public TicketView create(String email, Long workspaceId, CreateTicketRequest request) {
        var account = identity.current(email, workspaceId);
        if (request == null || request.submissionId() == null)
            TicketRules.invalid("A submission ID is required.");
        TicketRules.validate(request.content());
        // Serialize simultaneous retries for this author/key; the unique constraint is a second
        // guard.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                account.id() + ":" + request.submissionId());
        var existing = tickets.findByAuthorIdAndSubmissionId(account.id(), request.submissionId());
        if (existing.isPresent()) {
            if (!existing.get().getWorkspaceId().equals(workspaceId)
                    || !mapper.content(existing.get().getSubmissionContent())
                            .equals(request.content()))
                throw new KnowledgeException(
                        409,
                        "SUBMISSION_CONFLICT",
                        "This submission was already published with different content. Open the"
                                + " published ticket or start a new capture.");
            return mapper.view(existing.get());
        }
        var ticket = new TicketEntity();
        ticket.setAuthorId(account.id());
        ticket.setWorkspaceId(account.workspaceId());
        ticket.setAuthorName(account.fullName());
        ticket.setSubmissionId(request.submissionId());
        ticket.setContent(json.writeValueAsString(request.content()));
        ticket.setSubmissionContent(ticket.getContent());
        ticket.setCreatedAt(clock.instant());
        ticket.setClassificationStatus("PENDING");
        ticket.setAttributes("[]");
        ticket.setNextAttemptAt(clock.instant());
        return mapper.view(tickets.save(ticket));
    }

    /** Locks the row against edits and worker claims, then fences classification of old content. */
    @Override
    @Transactional
    public TicketView update(String email, Long workspaceId, Long id, UpdateTicketRequest request) {
        var account = identity.current(email, workspaceId);
        var ticket = editableTicket(id, account.workspaceId(), account.id());
        if (request == null || request.version() == null || request.version() < 0) {
            TicketRules.invalid("A valid ticket version is required.");
        }
        TicketRules.validate(request.content());
        boolean unchanged = mapper.content(ticket.getContent()).equals(request.content());
        if (request.version() != ticket.getVersion()) {
            // A lost response can be retried safely without classifying the same content again.
            if (unchanged) {
                return mapper.view(ticket);
            }
            throw new KnowledgeException(
                    409,
                    "TICKET_EDIT_CONFLICT",
                    "This ticket has changed since you opened it. Your edits are still here. Open"
                            + " the latest ticket in another tab to compare before editing again.");
        }
        if (unchanged) {
            return mapper.view(ticket);
        }
        ticket.setContent(json.writeValueAsString(request.content()));
        ticket.setVersion(ticket.getVersion() + 1);
        reclassify(ticket);
        return mapper.view(tickets.save(ticket));
    }

    private TicketEntity editableTicket(Long id, Long workspaceId, Long accountId) {
        var ticket =
                tickets.findForUpdate(id, workspaceId)
                        .orElseThrow(
                                () ->
                                        new KnowledgeException(
                                                404,
                                                "TICKET_NOT_FOUND",
                                                "This ticket is unavailable in your workspace."));
        if (!ticket.getAuthorId().equals(accountId)) {
            throw new KnowledgeException(
                    403, "TICKET_EDIT_FORBIDDEN", "Only the creator can edit this ticket.");
        }
        return ticket;
    }

    private void reclassify(TicketEntity ticket) {
        ticket.setClassificationVersion(null);
        ticket.setClassificationStatus("PENDING");
        ticket.setAttributes("[]");
        ticket.setAttempts(0);
        ticket.setNextAttemptAt(clock.instant());
        ticket.setLeaseToken(null);
        ticket.setLeaseUntil(null);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketView get(String email, Long workspaceId, Long id) {
        var account = identity.current(email, workspaceId);
        return tickets.findByIdAndWorkspaceId(id, account.workspaceId())
                .map(mapper::view)
                .orElseThrow(
                        () ->
                                new KnowledgeException(
                                        404,
                                        "TICKET_NOT_FOUND",
                                        "This ticket is unavailable in your workspace."));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketPage browse(
            String email,
            Long workspaceId,
            String detailsType,
            Integer limit,
            String encodedCursor) {
        var account = identity.current(email, workspaceId);
        var type = detailsType == null ? "" : detailsType;
        if (detailsType != null && !TicketRules.TEMPLATES.contains(type))
            TicketRules.invalid("Choose a supported Details type.");
        if (limit != null && limit < 1) TicketRules.invalid("Page size must be positive.");
        int size = limit == null ? 20 : Math.min(limit, 50);
        var cursor = TicketCursor.decode(encodedCursor, type);
        var rows = summaries.browse(account.workspaceId(), account.id(), type, cursor, size + 1);
        var items = rows.stream().limit(size).toList();
        String next = null;
        if (rows.size() > size) {
            var last = items.getLast();
            next = new TicketCursor(2, last.createdAt(), last.id(), type).encode();
        }
        return new TicketPage(items, next);
    }

    @Override
    public RefinementView refine(String email, Long workspaceId, RefinementRequest request) {
        identity.current(email, workspaceId);
        if (request == null
                || !("title".equals(request.field()) || "capture".equals(request.field()))) {
            TicketRules.invalid("Choose Title or Capture for refinement.");
        }
        TicketRules.validate(request.content());
        var mode = request.mode();
        String original = RefinementRules.original(request.content(), request.field());
        try {
            var text = ai.refine(original, request.field(), mode);
            TicketRules.text(
                    text,
                    RefinementRules.maxCharacters(original, request.field(), mode),
                    "Suggested wording");
            return new RefinementView(request.field(), text);
        } catch (RuntimeException exception) {
            throw new KnowledgeException(
                    503,
                    "REFINEMENT_UNAVAILABLE",
                    "Refinement is unavailable. Your writing is safe; retry or publish as"
                            + " written.");
        }
    }
}
