-- Ti'Cagou — migration initiale.
-- Les tables métier (structure, section, utilisateurs…) arrivent en phase 1.
-- Rappel : toute table métier porte structure_id ; montants XPF en BIGINT.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
