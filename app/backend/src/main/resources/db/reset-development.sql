-- MANUAL DEVELOPMENT RESET ONLY. Deletes application data in the current database.
-- Stop all backend instances first. Run once against each intended development DB.
-- Assumes application tables live in public. Never included by Liquibase.
BEGIN;
SET LOCAL lock_timeout = '10s';

-- Drop dependent tables before the accounts/workspaces they reference.
DROP TABLE IF EXISTS public.knowledge_ticket;
DROP TABLE IF EXISTS public.verification_email;
DROP TABLE IF EXISTS public.oauth_identity;
DROP TABLE IF EXISTS public.workspace_membership;
DROP TABLE IF EXISTS public.team_invitation;
DROP TABLE IF EXISTS public.refresh_session;
DROP TABLE IF EXISTS public.shedlock;
DROP TABLE IF EXISTS public.account;
DROP TABLE IF EXISTS public.workspace;
DROP TABLE IF EXISTS public.organization;

-- Dropping tables removes their triggers, indexes and owned identity sequences.
DROP FUNCTION IF EXISTS public.preserve_workspace_ticket();
DROP FUNCTION IF EXISTS public.preserve_personal_ticket();
DROP FUNCTION IF EXISTS public.preserve_individual_account();

-- Rewritten initialization scripts must run again, with fresh checksums.
DROP TABLE IF EXISTS public.databasechangelog;
DROP TABLE IF EXISTS public.databasechangeloglock;
COMMIT;

-- Restart the matching backend to recreate tables, foreign keys and indexes.
-- No CASCADE: unrelated dependencies cause this transaction to fail safely.
