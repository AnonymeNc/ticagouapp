/** Recharge l’application sur la crèche courante : session, poste et caches repartent de zéro (changement de crèche). */
export function restartApp(path = "/") {
  window.location.assign(path);
}
