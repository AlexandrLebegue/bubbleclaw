# BubbleClaw

Bulle de chat flottante pour Android, connectée à **ton propre gateway OpenClaw**
(API HTTP compatible OpenAI). Un homard un peu trop mignon vit par-dessus toutes
tes applications : tu le touches, il photographie l'écran, ouvre le chat avec le
contexte de ce que tu regardais, puis discute avec ton agent.

> BubbleClaw est un client personnel pour un gateway auto-hébergé. Il n'y a pas
> de serveur BubbleClaw, pas de compte, pas de télémétrie. Tout part uniquement
> vers l'URL que tu configures, y compris les rapports de crash si tu laisses
> l'option activée.

## Ce que ça fait

- **Bulle persistante** (`BubbleService`) en service de premier plan, draggable,
  qui se colle au bord le plus proche et mémorise sa position verticale et son
  côté (gauche/droite), y compris après rotation. Si elle était active au
  redémarrage, `BootReceiver` la relance après un reboot.
- **Capture d'écran sans popup MediaProjection** via l'API Accessibility
  (`takeScreenshot`, Android 11+), image réduite à 1080 px de large, JPEG q70,
  plus une vignette 320 px.
- **Contexte automatique** : nom de l'application au premier plan, URL de la page
  quand l'app est un navigateur connu, titre de fenêtre sinon, texte visible en
  option. La carte de contexte est une seule ligne compacte : icône de l'app,
  nom, page et vignette 36 dp. Toute la ligne ouvre la capture en plein écran.
- **Panneau de discussion flottant** : la fenêtre de chat est translucide et
  laisse voir l'application courante ; seule la carte du panneau est opaque
  (`#FF1B1518`, largeur 92 % plafonnée à 420 dp, hauteur maximale 52 % de la
  zone utile, coins arrondis 24 dp, bordure corail, ombre). Marge de 16 dp sur
  les côtés. À l'ouverture, la bulle se déplace en haut au centre et le panneau
  s'ouvre juste en dessous. Un tap hors du panneau le ferme.
- **Ouverture immédiate** : le panneau s'affiche au tap avec un squelette
  « Capture de l'écran… » et un shimmer, puis la carte se remplit quand la
  capture est prête, sans relancer l'activité. Le décodage, le redimensionnement
  et la compression JPEG tournent sur un executor dédié, jamais sur le thread
  principal.
- **Rendu markdown** : gras, italique, code inline, blocs de code, titres,
  listes à puces et numérotées, liens cliquables corail et séparateurs. Les
  messages de l'assistant sont rendus en `Spannable` par un parser et un
  renderer maison, tolérants au markdown partiel du streaming ; les messages
  utilisateur restent en texte simple.
- **Animation d'ouverture** : la bulle s'enfonce (0.86, 90 ms), glisse en haut
  au centre du téléphone (220 ms), puis le panneau se déploie depuis le centre
  de la bulle (0.85 → 1.0, fondu 180 ms). À chaque tap, le homard plisse les
  yeux et lève ses pinces pendant 460 ms. La fermeture inverse dure 140 ms puis
  la bulle remonte et retrouve sa position précédente. Retour haptique léger.
- **Second tap sur la bulle** : si la discussion est déjà ouverte, le même tap
  joue l'animation inverse et ferme le panneau, sans nouvelle capture ni
  réouverture, même si une capture est encore en cours (elle est annulée). L'état
  est tenu par `BubbleService`, renseigné par `ChatActivity` à la création et
  libéré à la destruction (rotation, retour arrière, fermeture par tap
  extérieur), donc une activité morte ne bloque jamais la bulle.
- **Chat en streaming SSE** vers `chat/completions`, avec repli automatique en
  non-streaming, messages d'erreur en français et bouton « Réessayer ». Pendant
  une réponse, le bouton d'envoi devient « Annuler la réponse » : il coupe la
  connexion en cours, efface le texte partiel et rend la main immédiatement.
- **Copie et lisibilité** : un appui long sur une bulle de message copie son
  contenu. Les messages utilisateur utilisent un texte sombre sur le corail
  pour rester lisibles (contraste renforcé).
- **Diagnostic sans adb** : un écran `Diagnostic` dans les réglages affiche la
  dernière trace de crash (horodatage + texte brut sélectionnable), la version
  de l'app, le modèle Android, l'état de l'accessibilité et de l'overlay, l'URL
  et le modèle configurés (jamais le jeton), et les 20 dernières lignes du
  journal interne. Des boutons copient ou partagent la trace et le journal.
- **Journal interne borné** : `Diag.log(...)` écrit dans `filesDir/diag.log`,
  plafonné à 200 Ko, les événements clés (démarrage, tap sur la bulle, capture,
  ouverture/fermeture du panneau, changements d'état, erreurs réseau). Aucun
  contenu de conversation n'y est enregistré.
- **Rapports de crash consentis** : `CrashReporter` écrit toujours la trace en
  local et, si le réglage `Envoyer les rapports de crash` est actif (par
  défaut), la POSTe vers `{gateway}/chat/completions` avec `user: android:crash`
  sur un thread limité à 2,5 s. La trace ne part que vers l'URL configurée.
- **Conversation persistante** : le fil est enregistré dans
  `filesDir/conversation.json` (écriture atomique par fichier temporaire puis
  `rename`), rechargé à chaque ouverture, et survit donc à la fermeture du
  panneau, à la rotation et aux relances. Les captures jointes sont copiées dans
  `cacheDir/conversation` et référencées par chemin, jamais en base64 dans le
  JSON ; les fichiers orphelins sont nettoyés.
- **Historique envoyé au gateway** : tout le fil utile (hors erreurs) dans
  l'ordre, plafonné aux 40 derniers messages. Le premier message de la session
  porte l'en-tête de contexte et l'image ; les suivants n'envoient que le texte.
  Une icône appareil-photo dans la barre permet de rattacher une nouvelle
  capture au message suivant.
- **Session stable** : le même identifiant de session est réutilisé d'un
  lancement à l'autre, donc la conversation continue côté gateway.
- **Animation de réflexion** : bulle dédiée avec le homard en humeur Thinking,
  trois points qui rebondissent en décalé (boucle 900 ms, décalage 150 ms) et
  légère pulsation. Elle disparaît dès le premier morceau de texte reçu ; aucun
  texte « … » ou « Réflexion… » n'est affiché.
- **Mode contrôle** (désactivé par défaut) : l'agent peut appeler des outils
  locaux (`tap`, `swipe`, `scroll`, `type_text`, `press_key`, `open_app`,
  `screenshot`). Les coordonnées sont exprimées dans celles de la capture, puis
  converties en pixels écran. Six allers-retours d'outils maximum par question.
  Quand le mode est off, **aucun** tableau `tools` n'est envoyé au modèle.
- **Mascotte dessinée au Canvas** : `LobsterView` (idle qui flotte et cligne des
  yeux, réflexion avec pinces qui tapotent, joie avec yeux `^ ^`) et
  `LobsterBubbleView` (badge circulaire, halo pulsé, pastille verte). Le homard
  interne au badge oscille en continu (3 dp, 3,6 s, rotation 1,5° en opposition
  de phase ; 5 dp et 1,8 s en Thinking). Seule la vue interne bouge, jamais la
  fenêtre overlay : le drag et les taps restent exacts.

## Build

Prérequis : JDK 21, Android SDK installé dans `/home/dev/android-sdk` (déjà
référencé par `local.properties`). Le projet cible AGP 8.7.3, Kotlin 2.0.21,
`compileSdk 35`, `minSdk 30`, JVM 17.

```bash
cd /home/dev/projects/bubbleclaw
ANDROID_HOME=/home/dev/android-sdk \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
./gradlew assembleDebug --no-daemon
```

APK généré :

```
app/build/outputs/apk/debug/app-debug.apk
```

Pour garder une copie de livraison :

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/bubbleclaw-debug.apk
```

Ne pas modifier `gradle.properties` : la mémoire est calibrée pour cette machine
(`-Xmx1536m`, 2 workers, pas de daemon).

Installation sur un téléphone branché :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

Ouvrir l'app → **Réglages** (ou appui long sur la bulle → Réglages).

| Champ | Défaut | Rôle |
|---|---|---|
| URL du gateway | `http://adresse-du-gateway:18789/v1` | Base HTTP. Avec ou sans `/v1` : BubbleClaw ajoute `/v1` seulement s'il manque. |
| Jeton | vide | Envoyé en `Authorization: Bearer …`. |
| Modèle | `openclaw/boss` | Champ `model` de la requête. |
| Session | `android:bubbleclaw` | Champ `user`, stable entre les lancements. |

Interrupteurs :

- **Capturer l'écran à chaque ouverture** (on) : sans capture, le chat fonctionne
  mais sans image ni carte de contexte.
- **Inclure le texte visible** (off) : ajoute un digest des textes de la fenêtre
  active au premier message (max 1500 caractères).
- **Mode contrôle** (off) : autorise les outils d'action sur l'écran.
- **Réponses en streaming** (on) : désactivable pour forcer le mode non-stream.
- **Envoyer les rapports de crash** (on) : la trace part uniquement vers l'URL
  du gateway configuré, jamais ailleurs. Désactivé, seul le fichier local
  `filesDir/crash-*.txt` est écrit.

**Tester la connexion** fait un `GET /v1/models` et affiche les modèles trouvés,
ou l'erreur réseau en français.

## Permissions demandées et pourquoi

- **Affichage par-dessus les autres apps** (`SYSTEM_ALERT_WINDOW`) : c'est la
  bulle flottante.
- **Service d'accessibilité** : capture d'écran native, lecture du nom de l'app,
  de l'URL du navigateur, du texte visible, et exécution des gestes en mode
  contrôle. Rien n'est lu en continu : la capture et le digest sont demandés au
  moment où tu touches la bulle ou quand un outil `screenshot` est appelé.
- **Notifications** (Android 13+) : notification du service de premier plan, avec
  une action « Arrêter ».
- **Internet** : uniquement vers le gateway configuré, conversations et rapports
  de crash consentis inclus.
- **RECEIVE_BOOT_COMPLETED** : `BootReceiver` relance la bulle au démarrage du
  téléphone si elle était active et si l'autorisation d'overlay est toujours
  accordée.

L'écran principal et les réglages affichent une checklist **en direct** de ces
autorisations, avec un bouton par ligne. Si le service d'accessibilité est coupé,
le chat s'ouvre quand même et explique ce qui manque au lieu de planter.

## Architecture (décisions techniques)

```
app/src/main/java/dev/bubbleclaw/
  BubbleClawApp.kt        initialisation des préférences
  AppPrefs.kt             SharedPreferences (URL, jeton, modèle, session, options)
  ScreenReaderService.kt  AccessibilityService : capture, app, URL, digest, gestes
  BubbleService.kt        service foreground + overlay WindowManager
  BootReceiver.kt         relance la bulle après un redémarrage
  CrashReporter.kt        trace de crash locale + envoi consenti au gateway
  Diag.kt                 journal interne borné à 200 Ko (sans contenu de chat)
  LobsterBubbleView.kt    badge circulaire animé
  LobsterView.kt          mascotte Canvas (idle / thinking / happy)
  ContextHolder.kt        passage en mémoire entre bulle et chat + snapshot contexte
  ChatActivity.kt         panneau flottant, carte contexte, rendu des messages
  ChatViewModel.kt        conversation persistée, boucle réseau/outils, rotation
  MarkdownParser.kt       parser markdown pur Kotlin (blocs + inline)
  MarkdownRenderer.kt     conversion du parser en Spannable Android
  ShimmerDrawable.kt      shimmer du squelette de contexte
  ConversationStore.kt    JSON atomique, chemins d'images et nettoyage du cache
  ThinkingIndicatorView.kt bulle de réflexion + points animés
  MaxHeightScrollView.kt  plafonne la hauteur du fil dans le panneau
  ApiClient.kt            HttpURLConnection + org.json, SSE et non-stream
  ControlTools.kt         schémas JSON des outils + exécution locale
  Permissions.kt          checklist d'autorisations partagée
  MainActivity.kt         onboarding + accueil
  SettingsActivity.kt     réglages, test gateway, checklist, bulle on/off
  DiagnosticActivity.kt   trace de crash, état app, journal, copier/partager
```

Choix assumés :

- **Aucune nouvelle dépendance** : `HttpURLConnection`, `org.json`,
  `Executors`/`Thread`/`Handler`. Les seules libs sont celles du squelette
  (AndroidX core/appcompat/material/constraintlayout/lifecycle).
- **Pas de Compose**, uniquement des Views classiques, layouts XML et vues
  construites à la main pour les bulles de message.
- **Fichier de capture + `ContextHolder`** : l'image est écrite dans
  `cacheDir/captures`, seuls des chemins passent par l'Intent (pas de limite
  Binder), et le `ContextHolder` sert de raccourci en mémoire. Le cache est
  élagué aux 20 captures les plus récentes.
- **Panneau flottant plutôt qu'overlay dans le service** : `ChatActivity`
  utilise une fenêtre plein écran translucide, sans dim système ni fond opaque,
  avec un panneau flottant dessiné dans l'activité. C'est le choix le plus
  stable sur Android 12–16 : l'application courante reste visible autour du
  panneau, l'IME reste géré par la fenêtre de l'activité (`adjustResize` +
  insets IME), la rotation et la sauvegarde d'état sont natives, et le service
  ne garde que la bulle.
- **`ChatViewModel`** garde les messages, le texte en cours de streaming et la
  boucle d'outils ; `ConversationStore` écrit chaque ajout dans un fichier
  temporaire puis le renomme. L'activité ne fait que rendre l'état.
- **Images de conversation** : le premier message (ou une reprise de contexte)
  copie la capture dans `cacheDir/conversation` pour que l'élagage des captures
  ne casse pas l'historique ; le JSON ne contient que le chemin.
- **Effacement** : action confirmée dans le menu long-press de la bulle et dans
  les réglages ; elle vide le JSON, le cache d'images, le fil en mémoire et,
  si le panneau est ouvert, l'activité se met à jour immédiatement.
- **Confidentialité des crashes** : les traces sont d'abord écrites dans
  `filesDir`. L'envoi au gateway est un réglage explicite, actif par défaut,
  limité à l'URL configurée et à un thread court ; le jeton n'apparaît jamais
  dans l'écran de diagnostic. Désactivé, rien ne quitte l'appareil.
- **Mode contrôle strictement conditionnel** : la liste `tools` est construite à
  chaque requête seulement si `controlMode` est vrai **et** que le service
  d'accessibilité est connecté. Sinon la clé `tools` est absente du JSON.
- **Capture des outils** : après `screenshot()`, l'image fraîche est renvoyée
  comme message utilisateur avec `data:image/jpeg;base64,…`, ce qui permet au
  modèle de relire l'écran sans dépendre d'un format de tool-result exotique.
- **Menu de la bulle** : `PopupMenu` ancré sur la vue overlay ; en cas d'échec du
  token de fenêtre, un message explique que le menu est momentanément
  indisponible plutôt que de planter.

## Utilisation

1. Accorder l'overlay, le service d'accessibilité et les notifications.
2. Renseigner l'URL du gateway et le jeton, puis **Tester la connexion**.
3. **Démarrer la bulle**. Le homard apparaît, se déplace et se colle aux bords.
4. Toucher le homard : capture + ouverture du chat avec le contexte.
5. Appui long : ouvrir le chat, basculer le mode contrôle, réglages, effacer la
   conversation, masquer.
6. Dans le chat, la pastille verte et le homard joyeux signalent une réponse
   arrivée.

## Vérifié au build / à tester sur un vrai téléphone

Vérifié ici : compilation `assembleDebug` verte après chaque point, test JVM
`testDebugUnitTest` vert (markdown : gras, code inline, bloc de code, listes,
markdown partiel), ressources et manifeste liés, APK produit. La version 0.7
ajoute l'écran de diagnostic, le journal borné et l'envoi de crash consenti.
Le panneau suit les insets IME, la conversation est relue depuis
`conversation.json`, les
animations sont démarrées/arrêtées avec le cycle de vie, et le chat vide affiche
un accueil court (une phrase + le homard) au lieu d'un écran blanc.

À valider sur appareil (nécessite un gateway et un téléphone réels) :

- capture réelle via le service d'accessibilité sur différentes apps ;
- extraction de l'URL sur Chrome, Firefox, Brave, Edge, Samsung Internet, Opera,
  DuckDuckGo, Vivaldi, Chromium ;
- position de la bulle après rotation et redémarrage ;
- affichage du menu long-press au-dessus d'autres apps ;
- streaming SSE réel, gestion des tool calls et des six itérations ;
- exécution des gestes et du texte dans des champs variés ;
- comportement des insets sur Android 12 à 16 (panneau, IME, scrim, tap extérieur) ;
- persistance réelle du fil après fermeture, rotation et redémarrage ;
- animation de réflexion et disparition au premier chunk SSE ;
- déplacement de la bulle en haut au centre, réaction yeux plissés/pinces levées,
  fermeture au second tap même pendant une capture ;
- oscillation du homard dans la bulle et pause écran éteint ;
- autorisation notifications sur Android 13/14/15/16 ;
- ouverture du panneau et démarrage du service au boot sur Android 16 ;
- réception réelle d'un rapport de crash par le gateway et lecture de l'écran
  de diagnostic sans adb ;
- fluidité perçue de l'ouverture (< 150 ms) et du déploiement depuis la bulle ;
- rendu markdown réel pendant un flux SSE et ouverture des liens.

## Notes

Code original écrit pour ce dépôt. Le projet PokeClaw (Apache-2.0) a servi de
référence de patterns Accessibility/overlay ; aucun code d'Andee (licence
custom) n'a été utilisé.
