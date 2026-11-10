/** Zone qui défile dans la coquille du poste bureau (la barre latérale et la barre supérieure restent fixes). */
export const CONTENT_SCROLL_ID = "contenu-defilant";

/** Remonte en haut de la page : changement de page, de rubrique des Paramètres… */
export function scrollContentToTop() {
  document.getElementById(CONTENT_SCROLL_ID)?.scrollTo?.({ top: 0 });
}

/** Amène un élément dans la zone visible (formulaire ouvert plus bas dans la page), sans animation : le défilement doux
 * ne se déroule pas dans tous les navigateurs. */
export function scrollIntoContent(element: Element | null) {
  element?.scrollIntoView?.({ block: "start" });
}
