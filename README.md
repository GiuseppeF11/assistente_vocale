# Assistente Vocale (Android, offline)

Assistente vocale leggero: **chiamare**, **avviare la navigazione**, **mettere musica su Spotify**,
**impostare sveglie e timer**.
Frase di attivazione riconosciuta da Vosk, sempre in locale. I comandi li capisce il riconoscitore
vocale del telefono (Google, sul dispositivo e offline quando l'italiano è scaricato), con Vosk come riserva.

## Installare e aggiornare

**Dal telefono**, apri questo link e installa il file scaricato (consenti le «origini sconosciute»):

**https://github.com/GiuseppeF11/assistente_vocale/releases/latest/download/AssistenteVocale.apk**

Oppure inquadra il QR code:

![QR per installare](docs/installa-qr.png)

**Aggiornamenti**: a ogni modifica GitHub compila e pubblica una nuova versione. Aprendo l'app compare
il pulsante verde **«Aggiorna a 1.N»**: un tocco, scarica e installa (la prima volta Android chiede di
consentire «Installa app sconosciute» per l'app; dalla seconda in poi su Android 12+ può aggiornare senza
altre conferme). Dopo l'aggiornamento, se l'ascolto a voce era attivo, una notifica invita a riaprire l'app.
Internet serve solo per questo controllo: il riconoscimento vocale resta sul telefono.

## Firma

L'APK è una build di produzione firmata con una chiave privata che sta solo nei segreti di GitHub
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`), non nel repository. Senza quei segreti la compilazione si ferma.
Conservane una copia: con una chiave diversa gli aggiornamenti non si installano sopra la versione esistente.

## Come funziona

1. Un servizio in primo piano tiene aperto il microfono e aspetta la frase di attivazione
   (predefinita **«ciao assistente»**, modificabile nell'app).
2. Alla frase di attivazione (verificata due volte, vedi sotto): segnale acustico e un **popup** in
   basso con il microfono che pulsa. A telefono sbloccato il popup compare sopra l'app in uso, senza
   aprire niente; a telefono bloccato lo schermo si accende e lo stesso popup appare sopra il blocco.
3. Mentre parli, il testo capito compare parola per parola.
4. A fine frase:
   - comando chiaro → lo esegue (per le chiamate c'è 1,8 s per toccare «Annulla»);
   - comando incompleto o ambiguo → chiede a voce e a schermo, poi riascolta.

| Dici | Succede |
|---|---|
| «chiama Mario Rossi» | chiama |
| «chiama Mario» (due Mario in rubrica) | «Ho trovato Mario Rossi o Mario Bianchi. Quale?» |
| «chiama mamma» (più numeri, nessun predefinito) | «Mamma ha più numeri: cellulare o casa?» |
| «chiama mamma sul cellulare» | chiama direttamente il cellulare |
| «chiama Luca in vivavoce» | chiama con l'altoparlante (o attiva «sempre in vivavoce» nell'app) |
| «chiama il numero tre tre tre uno due…» | chiama il numero dettato |
| «chiama» | «Chi vuoi chiamare?» |
| «portami a Catania» / «naviga verso via Etnea centoventi» | apre la navigazione guidata |
| «portami a casa» / «al lavoro» | usa gli indirizzi salvati nell'app |
| «portami» | «Dove vuoi andare?» |
| «metti Albachiara da Spotify» | cerca e riproduce su Spotify |
| «metti l'album Nevermind» / «la playlist Relax» / «qualcosa dei Coldplay» | album, playlist, artista |
| «metti» | «Cosa vuoi ascoltare?» |
| «sveglia alle sette e mezza» / «alle 7 meno un quarto di sera» | sveglia nell'app Orologio |
| «svegliami tra 20 minuti» | sveglia all'orario calcolato |
| «timer di 5 minuti» / «di un'ora e mezza» / «avvisami tra 10 minuti» | timer nell'app Orologio |
| «metti una sveglia» / «metti un timer» | «A che ora?» / «Per quanto tempo?» |
| «annulla», «lascia stare» | chiude |

Alle domande si risponde a voce («Rossi», «il secondo», «cellulare») oppure toccando l'opzione.
Dopo due incomprensioni di fila, o 7 secondi di silenzio, l'assistente si chiude da solo.

## Compilare

Serve Android Studio (qualsiasi versione recente) e un telefono con Android 10 o superiore.

1. Apri la cartella `AssistenteVocale` in Android Studio e lascia sincronizzare Gradle.
2. Premi Run. Alla prima build Gradle scarica il modello vocale italiano (~50 MB) in
   `app/src/main/assets/model-it`.
   Se lo scaricamento fallisce: scarica a mano `vosk-model-small-it-0.22.zip` da
   <https://alphacephei.com/vosk/models> ed estrai il **contenuto** della cartella interna
   in `app/src/main/assets/model-it` (deve esistere `model-it/am/final.mdl`).
3. Nell'app: concedi i permessi, attiva «Mostra sopra altre app», escludi l'app dal risparmio
   batteria, inserisci gli indirizzi di casa e lavoro, poi «Avvia ascolto».

Test della logica (parser, numeri, rubrica): `./gradlew test`.

### APK senza Android Studio (GitHub Actions)

Il progetto contiene `.github/workflows/apk.yml`: caricato su un repository GitHub, a ogni
modifica compila da solo l'APK. Si scarica da **Actions → ultima esecuzione → Artifacts →
AssistenteVocale-apk** (uno zip con dentro `app-debug.apk`), poi si copia sul telefono e si installa
consentendo le «origini sconosciute».

## Come si attiva (a scelta nell'app)

| Modalità | Come | Microfono |
|---|---|---|
| A voce | dici la frase di attivazione | sempre in ascolto (pallino verde fisso) |
| Tasto o gesto | tieni premuto il tasto di accensione o fai il gesto dell'assistente | acceso solo durante il comando |
| Entrambi | tutte e due | sempre in ascolto |

Per il tasto/gesto l'app va scelta come **«App assistente digitale»** (pulsante nell'app, oppure
Impostazioni → App → App predefinite). Finché è scelta, «Ok Google» non risponde. Su alcuni telefoni
(Samsung, Xiaomi) la pressione lunga del tasto di accensione va associata all'assistente nelle
impostazioni del tasto laterale. Quando un'app è l'assistente, è anche il riconoscitore vocale
predefinito: questa inoltra tutto a Google, così le altre app che usano la dettatura continuano a funzionare.

## Voce

Nell'app si scelgono motore (Google, Samsung, …), voce italiana e velocità, con un pulsante di prova.
«Automatica» usa la migliore voce italiana offline installata.

## Spotify

Il comando standard di Android («cerca e riproduci») Spotify lo tratta come una semplice ricerca. Per far
partire davvero la musica l'app parla con il **lettore di Spotify** (la sessione multimediale, quella che
si controlla dalla tendina) e gli chiede la ricerca vocale, come fanno Android Auto e gli assistenti:
funziona anche a telefono bloccato e senza aprire Spotify. Se Spotify è chiuso, lo si sveglia con il
tasto «play» (per un attimo riprende l'ultimo brano) e poi si chiede il brano giusto.

Serve l'**accesso alle notifiche** (pulsante nell'app). Su Android 13+ per le app installate da APK compare
«Impostazione limitata»: Impostazioni → App → Assistente Vocale → menu ⋮ → «Consenti impostazioni limitate».
Senza il permesso, o se Spotify ignora la richiesta, si apre la ricerca.

## Segnale di ascolto

Tipo di suono (bip, doppio bip, conferma, pip, un suono di notifica del telefono, nessuno), volume
(relativo al volume multimediale) e vibrazione facoltativa. La registrazione parte quando il suono è finito.

## Frase di attivazione: doppia verifica

Il rilevatore conosce solo le parole della frase e tende a "forzarci dentro" qualunque suono
(dici «ok» e lui sente «ok alfio»). Quando crede di averla sentita, gli ultimi 3 secondi di audio
vengono riascoltati con il riconoscimento completo: se la frase non c'è, l'attivazione viene scartata
e la riga «Sento» dell'app mostra «… → non è «ok alfio», ignoro».

## Se non capisce

- **Frase di attivazione**: nell'app, con l'ascolto attivo, la riga «Sento: …» mostra cosa riconosce
  il rilevatore. Se non compare niente o solo una parola, scegli un'altra frase (due parole, comuni
  ma insolite insieme). Il salvataggio rifiuta le parole che il modello non conosce.
- **Comandi**: tieni attivo «Usa il riconoscimento del telefono» e premi «Scarica l'italiano offline».
  Sotto compare quale motore ha capito l'ultimo comando.
- **Voce**: se manca la voce italiana l'assistente resta muto (meglio che parlare con pronuncia
  inglese): «Installa / aggiorna la voce italiana».

## Struttura

```
app/src/main/java/it/assistente/vocale/
  WakeService.kt        servizio: microfono, parola di attivazione, dialogo, voce
  AssistantActivity.kt  schermata sopra il blocco schermo
  MainActivity.kt       configurazione e permessi
  AssistantBus.kt       stato condiviso servizio <-> schermate
  Support.kt            rubrica, chiamata, navigazione, notifiche, modello, preferenze
  logic/                codice puro, senza Android, coperto da test
    CommandParser.kt    «chiama …» / «portami …» / «annulla»
    ItalianNumbers.kt   «centoventi» -> 120, numeri di telefono dettati
    Contacts.kt         ricerca tollerante in rubrica, risposte alle domande
```

Per aggiungere un comando: un nuovo caso in `Command` + regex in `CommandParser`,
poi il ramo corrispondente in `WakeService.onFinal`.

Frase di attivazione: si imposta dall'app (`Prefs.wakePhrase`); le parole devono esistere nel
vocabolario del modello Vosk (`graph/words.txt`).

## Limiti da conoscere

- **Schermo bloccato.** Le chiamate partono senza sbloccare. La navigazione no: Android non
  permette a Google Maps di aprirsi sopra il blocco, quindi l'assistente chiede lo sblocco
  (impronta/PIN) e poi avvia Maps.
- **Dopo un riavvio** Android (14+) vieta di riaccendere il microfono in automatico: arriva una
  notifica, basta toccarla (o aprire l'app) e l'ascolto riparte.
- **Microfono sempre in ascolto.** Per sentire la frase di attivazione il microfono deve restare
  aperto: Android mostra il **pallino verde** finché ascolta e nessuna app può nasconderlo (solo
  l'assistente di sistema preinstallato usa un canale privilegiato). La notifica dell'app invece è
  ridotta al minimo: nessuna icona nella barra di stato. Consumo di batteria continuo. Su Xiaomi, Samsung, Oppo ecc. va disattivato il risparmio batteria per l'app,
  altrimenti il sistema la chiude.
- **Nomi difficili.** Al riconoscitore del telefono vengono suggeriti i nomi della rubrica, e la
  ricerca tollera lettere sbagliate e differenze di pronuncia (Christian/Cristian); se non trova, chiede.
- **Vivavoce.** Si chiede al sistema di avviare la chiamata in vivavoce; se il telefono lo ignora,
  l'app accende l'altoparlante appena la chiamata parte. Su alcuni modelli può non funzionare.
- **Navigazione offline** solo se in Google Maps hai scaricato le mappe della zona.
- **Volume.** Segnale acustico e voce usano il volume multimediale.
