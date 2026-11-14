-- Départ bloqué validé à distance par la direction (maquette B5, ADR 0026) : la tablette demande, une personne de la
-- direction autorise ou refuse depuis son téléphone ou le bureau, avec un motif. La décision est tracée ici et au journal.
CREATE TABLE departure_request (
    id              UUID PRIMARY KEY,
    structure_id    UUID         NOT NULL REFERENCES structure (id),
    attendance_id   UUID         NOT NULL REFERENCES child_attendance (id),
    child_id        UUID         NOT NULL REFERENCES child (id),
    device_id       UUID         REFERENCES device (id),
    person_name     VARCHAR(170) NOT NULL,
    relationship    VARCHAR(120) NOT NULL,
    id_checked      BOOLEAN      NOT NULL,
    requested_by    VARCHAR(170) NOT NULL,
    requested_at    TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REFUSED', 'CANCELLED')),
    decided_by_id   UUID         REFERENCES app_user (id),
    decided_by      VARCHAR(170),
    decided_at      TIMESTAMPTZ,
    reason          VARCHAR(300)
);
CREATE INDEX departure_request_pending_idx ON departure_request (structure_id) WHERE status = 'PENDING';
