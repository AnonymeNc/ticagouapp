package nc.ticagou.portal;

import java.util.UUID;

/**
 * Un élément susceptible d'être partagé avec les parents a changé. Publié dans la transaction de la modification ;
 * {@link PortalPublisher} l'ajoute à la file d'envoi si le portail est actif. Le contenu est construit à l'envoi.
 */
public record PortalChange(UUID structureId, PortalItemType type, UUID entityId) {
}
