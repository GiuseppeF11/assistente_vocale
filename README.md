# Assistente Vocale (Android, offline)

Assistente vocale leggero che fa due cose: **chiamare** e **avviare la navigazione**.
Riconoscimento vocale interamente sul telefono (Vosk), nessun server, nessuna libreria oltre a Vosk.

## Come funziona

1. Un servizio in primo piano tiene aperto il microfono e aspetta **«ciao assistente»**.
2. Alla parola di attivazione: segnale acustico, lo schermo si accende e compare la schermata
   dell'assistente (anche sopra il blocco schermo) con il microfono che pulsa.
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
| «chiama il numero tre tre tre uno due…» | chiama il numero dettato |
| «chiama» | «Chi vuoi chiamare?» |
| «portami a Catania» / «naviga verso via Etnea centoventi» | apre la navigazione guidata |
| «portami a casa» / «al lavoro» | usa gli indirizzi salvati nell'app |
| «portami» | «Dove vuoi andare?» |
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

Parola di attivazione: costante `WAKE_PHRASE` in `WakeService`. Deve essere fatta di parole
italiane comuni (devono esistere nel vocabolario del modello).

## Limiti da conoscere

- **Schermo bloccato.** Le chiamate partono senza sbloccare. La navigazione no: Android non
  permette a Google Maps di aprirsi sopra il blocco, quindi l'assistente chiede lo sblocco
  (impronta/PIN) e poi avvia Maps.
- **Dopo un riavvio** Android (14+) vieta di riaccendere il microfono in automatico: arriva una
  notifica, basta toccarla (o aprire l'app) e l'ascolto riparte.
- **Batteria e privacy.** Il microfono resta sempre attivo: pallino verde fisso e consumo
  continuo. Su Xiaomi, Samsung, Oppo ecc. va disattivato il risparmio batteria per l'app,
  altrimenti il sistema la chiude.
- **Nomi difficili.** Il modello piccolo sbaglia più facilmente cognomi rari e località
  straniere. La ricerca in rubrica tollera una o due lettere sbagliate; se non trova, chiede.
- **Navigazione offline** solo se in Google Maps hai scaricato le mappe della zona.
- **Volume.** Segnale acustico e voce usano il volume multimediale.
