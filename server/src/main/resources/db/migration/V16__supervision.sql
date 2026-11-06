-- Supervision à distance (ADR 0012), activée par la direction : état technique envoyé toutes les 15 minutes au
-- service hébergé du prestataire. Indépendante du portail parents (paire de clés et activation distinctes).
CREATE TABLE monitoring_settings (
    structure_id    UUID PRIMARY KEY REFERENCES structure (id),
    state           VARCHAR(10)  NOT NULL DEFAULT 'DESACTIVE' CHECK (state IN ('DESACTIVE', 'ACTIF')),
    service_url     VARCHAR(255),
    site_id         UUID,
    activated_at    TIMESTAMPTZ,
    activated_by    VARCHAR(170),
    last_success_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(500),
    failures        INTEGER      NOT NULL DEFAULT 0
);
