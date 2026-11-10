-- Barème de la formule unique (ADR 0021), aligné sur l'offre de Meeko : 15 000 F HT par mois quelle que soit la taille
-- (jusqu'à 80 places ; au-delà, sur devis), support rapide compris (éditeur sur place, en Nouvelle-Calédonie) : plus de
-- Support Premium payant. Équipement, remises et prestations inchangés.
-- Nouvelle version datée : les contrats signés avant le 01/11/2026 gardent le prix du « Barème 2026 ».

UPDATE price_list SET valid_to = DATE '2026-10-31'
WHERE id = '00000000-0000-0000-0000-000000002026' AND (valid_to IS NULL OR valid_to > DATE '2026-10-31');

INSERT INTO price_list (id, label, valid_from, micro_max, structure_max, grande_max, included_desktops, included_tablets,
                        engagement3_discount, extra_structure_discount, created_at)
VALUES ('00000000-0000-0000-0000-000000202611', 'Barème formule unique', DATE '2026-11-01', 15, 40, 80, 1, 2, 100, 150, now());

INSERT INTO price_item (id, price_list_id, kind, code, tier, label, price, unit, includes, tgc_nature, position)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000202611', kind, code, tier, label, price, unit, includes, nature, position
FROM (VALUES
    ('FORMULE', 'COMPLET', 'MICRO', 'Ti''Cagou Micro', 15000, NULL, NULL, 'ABONNEMENT', 10),
    ('FORMULE', 'COMPLET', 'STRUCTURE', 'Ti''Cagou Structure', 15000, NULL, NULL, 'ABONNEMENT', 11),
    ('FORMULE', 'COMPLET', 'GRANDE', 'Ti''Cagou Grande', 15000, NULL, NULL, 'ABONNEMENT', 12)
) AS seed (kind, code, tier, label, price, unit, includes, nature, position);

-- Équipement et prestations : repris de la version précédente, aux mêmes prix.
INSERT INTO price_item (id, price_list_id, kind, code, tier, label, price, unit, includes, tgc_nature, position)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000202611', kind, code, tier, label, price, unit, includes, tgc_nature, position
FROM price_item
WHERE price_list_id = '00000000-0000-0000-0000-000000002026' AND kind IN ('EQUIPEMENT', 'PRESTATION');
