-- Une seule formule, tout compris (ADR 0021). Elle garde son code COMPLET : les contrats et les barèmes déjà saisis la
-- désignent ainsi. Seul son libellé change, sur les lignes de facture à venir.
-- Les prix des anciennes formules (ESSENTIEL, GESTION) et des modules à la carte restent dans les barèmes : les
-- contrats signés avant se facturent jusqu'à leur avenant, qui passe à la formule unique. Prix de la formule unique à
-- confirmer (docs/commercial/bareme-tarifaire.md).

UPDATE price_item
SET label = CASE tier WHEN 'MICRO' THEN 'Ti''Cagou Micro' WHEN 'STRUCTURE' THEN 'Ti''Cagou Structure' ELSE 'Ti''Cagou Grande' END
WHERE kind = 'FORMULE' AND code = 'COMPLET';
