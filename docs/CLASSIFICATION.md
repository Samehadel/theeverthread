# Automatic capture classification

Personal life and professional knowledge are equally supported capture contexts.
Publication remains immediate; background classification never blocks it.

## Catalog and selection rules

`KNOWLEDGE_ATTRIBUTE` is the shared catalog and its only production source of
values. Liquibase migration `20261027-001-attribute-catalog.sql` creates the table
and seeds all 40 existing IDs, display labels, and inclusion/exclusion descriptions
(including label-specific examples).
Each entry also has `SORT_ORDER` and `ACTIVE`. The Java and TypeScript catalogs
are removed; the prompt contains general classification rules rather than a fixed
list of options or label-specific examples.

`AttributeCatalog` reads active definitions from the database for each job. It
passes the same immutable map to the LLM and result validator. The job can finish
against its original snapshot if a catalog update happens while the provider is
running. The next job reads the new catalog. An empty active catalog completes
with no attributes and no provider call. Database/provider failures use the
existing retry path and never undo publication.

`GET /api/knowledge/tickets/attributes` returns display labels keyed by ID. It
requires an authenticated active member and the selected `X-Workspace-Id`, just
like ticket reads. The catalog is shared product metadata, not workspace data.
Labels include inactive entries so historical tickets remain readable. The UI
reloads labels on screen entry and browse refresh, and follows catalog order.
If the endpoint is unavailable, previously loaded labels remain usable; unknown
IDs are displayed as a fallback while ticket content stays available.

## Updating the catalog

Add a new Liquibase changeset to insert an ID or update its label, description,
ordering, or active flag. Do not edit an applied seed migration or rename/delete
IDs already used by tickets. For example:

```sql
--liquibase formatted sql
--changeset theeverthread:example-add-gardening
INSERT INTO KNOWLEDGE_ATTRIBUTE (ID, DISPLAY_LABEL, DESCRIPTION, SORT_ORDER)
VALUES ('area:gardening', 'Gardening', 'Gardens, plants, and gardening practice.', 100);
```

Include the changeset in `db/changelog-master.yaml` and apply it through the normal
migration process. Java/TypeScript catalog edits and configuration changes are
not needed. Data edits become visible to subsequent jobs and UI catalog reads
without restarting the running backend. Liquibase must still be applied; editing
a migration file alone does not update an already-running database.

Set `ACTIVE=FALSE` to retire a category from future classification while preserving
its historical display label. A label-only update changes displayed wording but
not the ticket IDs or semantic classification fingerprint. Existing tickets are
never automatically reclassified when definitions change.

The classifier chooses at most six distinct, clearly supported IDs. No dimension
is required. Empty attributes are a successful no-match. Catalog descriptions
carry the boundaries for personal and professional topics. Templates are weak
hints; the model must not infer diagnoses or unstated sensitive traits.
The validator rejects IDs outside the job's active snapshot and excessive output.
It does not prove semantic correctness; that requires real-provider evaluation.

## Validation and model comparison

The fictional dataset at
`app/backend/src/test/resources/knowledge/classification-examples.json` lists
required labels and all acceptable labels for each example. Labels outside that
acceptable set count as false positives; missing required labels count separately.
It covers personal, professional, mixed, ambiguous, Arabic, keyword collisions,
misleading templates, and embedded instructions. These initial expectations are
implementation assumptions, to be refined with product-owner examples.

Normal `./gradlew check` validates fixtures against the migrated catalog, catalog
rules, prompt/payload wiring, database updates, authorization, and background
persistence using mocked provider responses. It makes no real AI
calls. To measure semantics, explicitly run from `app/backend` with a configured
`OPENAI_API_KEY`:

```sh
CLASSIFICATION_EVAL=true ./gradlew test --tests '*ClassificationEvaluationTests' --rerun-tasks --info
```

The live test sends only fictional examples to OpenAI and incurs provider usage.
It runs both the original catalog/prompt and the current classifier against the
same model, prints unexpected/missing label counts, and checks the new results
against the expected sets. The default is the application's current
`gpt-5.4-nano`; set `CLASSIFICATION_EVAL_MODEL` to compare another model. Review
several runs before claiming improvement or changing the production model.

## Versioning and existing results

The original additive migration creates nullable `CLASSIFICATION_VERSION`; the
catalog migration widens it to 100 characters without changing existing values.
Successful writes atomically store attributes, status, and `v3:<SHA-256>` behind
the existing lease-token fence. The fingerprint identifies the active ID/description
snapshot, independent of row order, and the prefix identifies the general prompt
revision. NULL identifies legacy results or no successful result yet; existing
`life-work-v2` values are preserved. Version metadata remains internal.

Deploy matching backend and frontend builds. Stop old classification workers
before starting the new backend so old prompts cannot write unversioned results
during the rollout. Existing labels are preserved, with no automatic bulk
provider calls. After evaluating the new classifier, an operator can requeue
specific legacy tickets in the intended workspace, for example using bound SQL
parameters:

```sql
UPDATE KNOWLEDGE_TICKET
SET CLASSIFICATION_STATUS = 'PENDING', ATTEMPTS = 0,
    NEXT_ATTEMPT_AT = CURRENT_TIMESTAMP, LEASE_TOKEN = NULL, LEASE_UNTIL = NULL
WHERE WORKSPACE_ID = :workspace_id
  AND ID IN (:reviewed_ticket_ids)
  AND (CLASSIFICATION_VERSION IS NULL OR CLASSIFICATION_VERSION = :previous_version)
  AND CLASSIFICATION_STATUS IN ('COMPLETE', 'FAILED');
```

This is an operator runbook, not an HTTP endpoint. Inspect the selected IDs first
and use a transaction to review the affected row count. Old attributes remain
until successful replacement; failure retains the published ticket and its old
labels. Active leases are excluded. No production reclassification is performed
by the migration or tests.

Architecture guidance consulted: `theeverthread-architecture` / `betterf-architecture`
`main` at `cdd59b639c3136a6311640b7f403a5aca60af5ba`. That committed version has no
classification-specific contract. Local uncommitted architecture documents were
not used or modified. This change extends the catalog and prompt within the
existing background-job design.
