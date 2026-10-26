--liquibase formatted sql
--changeset theeverthread:20261026-001-classification-version
-- NULL identifies legacy results or tickets not yet successfully classified.
ALTER TABLE KNOWLEDGE_TICKET ADD COLUMN CLASSIFICATION_VERSION VARCHAR(40);
--rollback ALTER TABLE KNOWLEDGE_TICKET DROP COLUMN CLASSIFICATION_VERSION;
