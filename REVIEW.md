# BubbleClaw — audit produit

Parcours complet de l'app, écran par écran et état par état, en jouant
l'utilisateur puis le relecteur produit. Chaque point est priorisé :

- **P0** : bloquant, contredit la promesse ou casse un parcours principal.
- **P1** : gênant, l'utilisateur peut se retrouver bloqué, trompé ou exclu.
- **P2** : polish, confort et finition.

## Synthèse

| Priorité | Trouvés | Corrigés | Reportés |
|---|---:|---:|---:|
| P0 | 4 | 4 | 0 |
| P1 | 11 | 11 | 0 |
| P2 | 13 | 3 | 10 |
| **Total** | **28** | **18** | **10** |

## P0 — bloquants (tous corrigés)

1. **Le fond de la discussion laissait voir l'app précédente.** La fenêtre
   était translucide, le scrim à 35 % et la fenêtre de l'activité transparente :
   bureau, papier peint et app précédente restaient visibles. **Corrigé** :
   fond plein écran `#F2141013` peint sur une fenêtre opaque, suppression de
   `windowIsTranslucent` et de l'alpha du scrim. Le panneau reste flottant,
   les marges et le tap extérieur sont conservés ; l'animation ne porte que sur
   le panneau, donc aucun flash pendant l'ouverture.
2. **Un second tap sur la bulle ne fermait pas la discussion.** Il relançait une
   capture, y compris en photographiant le panneau lui-même. **Corrigé** :
   `BubbleService` tient l'état « discussion ouverte », `ChatActivity` le
   renseigne à la création et le libère à la destruction. Un second tap joue
   l'animation inverse, ne capture rien, et l'état repart à zéro après un
   redémarrage du process.
3. **Aucun moyen d'annuler une réponse en cours.** Le bouton d'envoi restait
   désactivé, et fermer le panneau pendant un stream laissait la bulle bloquée
   en « réflexion » jusqu'au timeout de 120 s. **Corrigé** : le bouton devient
   « Annuler la réponse » pendant le stream, `ApiClient.cancel()` coupe la
   connexion, le ViewModel invalide le tour et remet la bulle en Idle. Fermer
   l'activité annule aussi proprement le tour en cours.
4. **Les traces de crash étaient envoyées automatiquement au gateway.** Cela
   contredisait la promesse « pas de télémétrie » et pouvait même déclencher une
   conversation côté agent. **Corrigé** : les traces restent dans `filesDir`,
   aucun envoi réseau.

## P1 — gênants (tous corrigés)

1. **URL de gateway vide ou invalide acceptée silencieusement.** L'app affichait
   ensuite une erreur réseau générique. **Corrigé** : validation `http(s)://`
   dans les réglages et dans `ApiClient`, messages en français explicites.
2. **Bulle hors écran après rotation.** X était conservé en pixels absolus.
   **Corrigé** : `onConfigurationChanged` replace la bulle sur le côté mémorisé
   et borne Y à la nouvelle hauteur.
3. **Aucun retour de la bulle après un reboot**, alors que
   `RECEIVE_BOOT_COMPLETED` était demandée. **Corrigé** : `BootReceiver` relance
   la bulle si elle était active et si l'overlay est autorisé.
4. **La bulle était invisible pour TalkBack.** Le Canvas custom n'avait aucune
   description. **Corrigé** : description dynamique « ouvrir » / « fermer ».
5. **Cibles tactiles de 40 dp** pour les boutons du panneau et les actions de la
   checklist. **Corrigé** : 48 dp minimum pour les boutons d'en-tête, Retour et
   les actions d'autorisation.
6. **Contraste insuffisant des messages utilisateur** (blanc sur corail clair).
   **Corrigé** : texte sombre `ink` sur le corail, contraste nettement renforcé.
7. **Flèche Retour des réglages sans libellé accessible.** **Corrigé** :
   `contentDescription="Retour"`.
8. **Mode contrôle activable sans accessibilité, sans explication.** Le mode
   était enregistré mais silencieusement inactif. **Corrigé** : avertissement
   dans les réglages et dans le menu de la bulle.
9. **Effacer la conversation laissait le panneau ouvert afficher l'ancien fil.**
   **Corrigé** : l'activité ouverte est vidée immédiatement, depuis la bulle
   comme depuis les réglages.
10. **Statut « En ligne » affiché avant toute connexion réussie**, même hors
    ligne. **Corrigé** : « Prêt » au départ, « Connexion… » pendant la requête,
    puis « En ligne » ou « Injoignable » selon le résultat réel.
11. **Notifications refusées sans explication.** **Corrigé** : message clair
    expliquant qu'Android peut masquer l'état de la bulle.

## P2 — polish

### Corrigés

1. **Côté de la bulle non mémorisé** : elle revenait toujours à droite.
   **Corrigé** : le côté gauche/droite est persisté avec Y.
2. **Impossible de copier un message.** **Corrigé** : appui long sur une bulle,
   toast « Message copié. ».
3. **Capture plein écran sans affordance de fermeture explicite.**
   **Corrigé** : description accessible « Capture de l'écran. Touche pour
   fermer. ».

### Reportés (et pourquoi)

1. **Horodatage des messages non affiché.** Le modèle stocke déjà `timestamp`,
   mais l'afficher demande de revoir la densité visuelle de chaque bulle ; à
   faire avec un choix de design (séparateurs de jour plutôt qu'une heure sur
   chaque bulle).
2. **Tableaux markdown affichés en texte brut avec les `|`.** Le parser maison
   ne gère pas les tableaux ; l'ajout d'un vrai rendu de tableau est un chantier
   disproportionné pour cette itération et doit rester sans dépendance.
3. **Lignes de code très longues : retour à la ligne plutôt que défilement
   horizontal.** Lisible mais dense ; un `HorizontalScrollView` par bloc de code
   alourdirait le rendu du stream.
4. **Onboarding non réouvrable après « C'est parti ».** Le contenu existe déjà
   dans l'accueil ; un bouton « Revoir » serait du confort, pas un blocage.
5. **Changer la session ne réinitialise pas le fil local.** Comportement
   volontaire (le fil est local, la session est côté gateway) mais mérite un
   libellé d'aide pour éviter la confusion.
6. **Le fil affiché n'a pas de limite de messages.** Le gateway est plafonné à
   40 messages, mais l'écran peut charger tout l'historique et ralentir sur des
   mois d'usage ; à tronquer ou virtualiser plus tard.
7. **Erreurs et changements de statut non annoncés par TalkBack.** Il faudrait
   des `announceForAccessibility` ciblés ; à faire avec une passe accessibilité
   dédiée.
8. **Réglages sans confirmation d'enregistrement.** Tout est sauvegardé à la
   frappe ; un retour visuel serait rassurant mais non nécessaire.
9. **Traces de crash stockées mais non consultables dans l'app.** Elles restent
   accessibles via `adb` ; un écran de diagnostic serait du confort.
10. **Les apps réellement plein écran / certains jeux peuvent masquer l'overlay**
    ou refuser les gestes d'accessibilité. Aucun contournement fiable côté app ;
    à documenter comme limite Android plutôt qu'à coder.

## Points parcourus sans défaut supplémentaire

- Premier lancement : les trois étapes et la checklist expliquent quoi faire ;
  l'absence d'accessibilité ouvre quand même le chat avec un message dédié.
- Overlay refusé : l'app ouvre l'écran système et explique l'autorisation
  manquante.
- Accessibilité coupée : capture et outils indisponibles mais aucun crash ;
  messages en français dans le panneau et les outils.
- Bulle : drag, seuil de tap, appui long, menu, masquage, collage aux bords.
- Panneau : ancrage haut/bas selon la bulle, rotation via ViewModel, insets IME,
  longs messages, blocs de code, erreurs, absence de réseau.
- Conversation : persistance atomique, images hors JSON, nettoyage des
  orphelins, ordre, doublons, reprise après erreur, effacement.
- Réglages : valeurs par défaut, test de connexion, jeton invalide, URL
  invalide, redémarrage/arrêt de la bulle.
- Cohérence : états vides, squelettes de chargement, animations d'ouverture et
  de fermeture, tailles de texte et espacements.

## Vérification

- `./gradlew assembleDebug testDebugUnitTest --no-daemon` : vert.
- Tests unitaires markdown : verts.
- APK : `app/build/outputs/apk/debug/app-debug.apk`.
