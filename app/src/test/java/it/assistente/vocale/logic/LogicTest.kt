package it.assistente.vocale.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    // ---------- comandi ----------

    @Test fun chiamate() {
        assertEquals(Command.Call("mario rossi", null), CommandParser.parse("chiama mario rossi"))
        assertEquals(Command.Call("mamma", null), CommandParser.parse("chiama la mamma"))
        assertEquals(Command.Call("mamma", NumberKind.HOME), CommandParser.parse("chiama mamma a casa"))
        assertEquals(Command.Call("luca", NumberKind.MOBILE), CommandParser.parse("telefona a luca sul cellulare per favore"))
        assertEquals(Command.Call("casa", null), CommandParser.parse("chiama casa"))
        assertEquals(Command.Call("giuseppe", null), CommandParser.parse("per favore fai una chiamata a giuseppe"))
        assertEquals(Command.Call("anna", null), CommandParser.parse("vorrei chiamare anna grazie"))
        assertEquals(Command.Call("tre tre tre uno due tre", null), CommandParser.parse("chiama il numero tre tre tre uno due tre"))
        assertEquals(Command.Call("avvocato", null), CommandParser.parse("chiama l'avvocato"))
        assertEquals(Command.CallWho, CommandParser.parse("chiama"))
        assertEquals(Command.CallWho, CommandParser.parse("telefona a"))
    }

    @Test fun vivavoce() {
        assertEquals(Command.Call("mario", null, true), CommandParser.parse("chiama mario in vivavoce"))
        assertEquals(Command.Call("mamma", NumberKind.MOBILE, true), CommandParser.parse("chiama mamma sul cellulare in vivavoce"))
        assertEquals(Command.Call("mamma", NumberKind.HOME, true), CommandParser.parse("chiama mamma col vivavoce a casa"))
        assertEquals(Command.Call("luca", null, true), CommandParser.parse("chiama in vivavoce luca"))
        assertEquals(Command.Call("anna", null, true), CommandParser.parse("telefona ad anna con l'altoparlante"))
        assertTrue(CommandParser.mentionsSpeaker("il secondo in vivavoce"))
        assertTrue(!CommandParser.mentionsSpeaker("il secondo"))
    }

    /** Il riconoscitore Google restituisce maiuscole, punteggiatura e cifre. */
    @Test fun testoDaGoogle() {
        assertEquals(Command.Call("mario rossi", null), CommandParser.parse("Chiama Mario Rossi."))
        assertEquals(Command.Navigate("via etnea 120 catania"), CommandParser.parse("Portami a Via Etnea 120, Catania"))
        assertEquals(Command.Call("avvocato", null), CommandParser.parse("Chiama l’avvocato"))
        assertEquals(Command.Cancel, CommandParser.parse("Annulla!"))
        assertEquals("+393331234567", ItalianNumbers.phoneDigits("+39 333 1234567"))
        assertEquals("3331234567", ItalianNumbers.phoneDigits("333-123-4567"))
        assertEquals(Command.Call("333 123 4567", null), CommandParser.parse("chiama il 333 123 4567"))
    }

    @Test fun fraseDiAttivazione() {
        assertEquals("ehi computer", Text.spoken("Ehi, Computer!"))
        assertEquals("perché no", Text.spoken("  Perché   no? "))
        assertEquals("giusepe", Text.phonetic("giuseppe"))
        assertEquals("cristian", Text.phonetic("christian"))
    }

    @Test fun verificaAttivazione() {
        assertTrue(WakeMatcher.matches("ok alfio", "ok alfio"))
        assertTrue(WakeMatcher.matches("ok alfio", "allora ok alfio chiama"))
        assertTrue(WakeMatcher.matches("ok alfio", "o calfio"))
        assertTrue(WakeMatcher.matches("ok alfio", "ok al fio"))
        assertTrue(WakeMatcher.matches("ciao assistente", "ciao assistenti"))
        // il caso segnalato: "ok" da solo non deve attivare
        assertTrue(!WakeMatcher.matches("ok alfio", "ok"))
        assertTrue(!WakeMatcher.matches("ok alfio", "ok va bene"))
        assertTrue(!WakeMatcher.matches("ok alfio", "ok alfa"))
        assertTrue(!WakeMatcher.matches("ok alfio", "alfio"))
        assertTrue(!WakeMatcher.matches("ciao assistente", "ciao"))
        assertTrue(!WakeMatcher.matches("ok alfio", ""))
    }

    @Test fun spotify() {
        fun play(q: String, f: MediaFocus = MediaFocus.ANY) = Command.Play(PlayRequest(q, f))
        assertEquals(play("bohemian rhapsody"), CommandParser.parse("metti bohemian rhapsody da spotify"))
        assertEquals(play("albachiara"), CommandParser.parse("Metti \"Albachiara\" su Spotify"))
        assertEquals(play("vasco rossi"), CommandParser.parse("metti vasco rossi"))
        assertEquals(play("nevermind", MediaFocus.ALBUM), CommandParser.parse("metti l'album nevermind su spotify"))
        assertEquals(play("coldplay", MediaFocus.ARTIST), CommandParser.parse("fammi sentire qualcosa dei coldplay"))
        assertEquals(play("relax", MediaFocus.PLAYLIST), CommandParser.parse("riproduci la playlist relax su spotify"))
        assertEquals(play("vita spericolata", MediaFocus.SONG), CommandParser.parse("metti la canzone vita spericolata"))
        assertEquals(play("ligabue"), CommandParser.parse("su spotify metti ligabue"))
        assertEquals(Command.PlayWhat, CommandParser.parse("metti"))
        assertEquals(Command.PlayWhat, CommandParser.parse("metti spotify"))
        // la navigazione resta navigazione
        assertEquals(Command.Navigate("palermo"), CommandParser.parse("avvia la navigazione per palermo"))
    }

    @Test fun sveglia() {
        assertEquals(Command.Alarm(7, 0), CommandParser.parse("metti la sveglia alle 7"))
        assertEquals(Command.Alarm(7, 30), CommandParser.parse("sveglia alle sette e mezza"))
        assertEquals(Command.Alarm(6, 45), CommandParser.parse("svegliami alle 6:45"))
        assertEquals(Command.Alarm(6, 45), CommandParser.parse("svegliami alle sette meno un quarto"))
        assertEquals(Command.Alarm(19, 15), CommandParser.parse("imposta una sveglia alle sette e un quarto di sera"))
        assertEquals(Command.Alarm(1, 0), CommandParser.parse("metti una sveglia all'una"))
        assertEquals(Command.Alarm(23, 0), CommandParser.parse("sveglia alle undici di notte"))
        assertEquals(Command.Alarm(2, 0), CommandParser.parse("sveglia alle due di notte"))
        assertEquals(Command.Alarm(12, 0), CommandParser.parse("sveglia a mezzogiorno"))
        assertEquals(Command.Alarm(8, 45), CommandParser.parse("Metti la sveglia per domani alle 8 e quaranta cinque."))
        assertEquals(Command.AlarmIn(1200), CommandParser.parse("svegliami tra 20 minuti"))
        assertEquals(Command.AlarmWhen, CommandParser.parse("metti una sveglia"))
        assertEquals(Command.Alarm(7, 30), CommandParser.parse("metti la sveglia alle 7.30"))
    }

    @Test fun timer() {
        assertEquals(Command.Timer(300), CommandParser.parse("metti un timer di 5 minuti"))
        assertEquals(Command.Timer(5400), CommandParser.parse("imposta un timer di un'ora e mezza"))
        assertEquals(Command.Timer(1800), CommandParser.parse("timer di mezz'ora"))
        assertEquals(Command.Timer(900), CommandParser.parse("timer di un quarto d'ora"))
        assertEquals(Command.Timer(90), CommandParser.parse("timer un minuto e mezzo"))
        assertEquals(Command.Timer(630), CommandParser.parse("conto alla rovescia di dieci minuti e trenta secondi"))
        assertEquals(Command.Timer(4800), CommandParser.parse("timer 1 ora e 20"))
        assertEquals(Command.Timer(25 * 60), CommandParser.parse("timer venti cinque minuti"))
        assertEquals(Command.Timer(600), CommandParser.parse("timer 10"))
        assertEquals(Command.Timer(600), CommandParser.parse("avvisami tra 10 minuti"))
        assertEquals(Command.TimerHowLong, CommandParser.parse("metti un timer"))
        assertEquals("1 ora e 30 minuti", TimeParser.describe(5400))
        assertEquals("1 minuto e 30 secondi", TimeParser.describe(90))
        // "metti" resta Spotify quando non si parla di sveglie o timer
        assertEquals(Command.Play(PlayRequest("vasco rossi", MediaFocus.ANY)), CommandParser.parse("metti vasco rossi"))
    }

    @Test fun confermeInAuto() {
        assertEquals(true, ChoiceParser.yesNo("sì"))
        assertEquals(true, ChoiceParser.yesNo("Sì, chiama"))
        assertEquals(true, ChoiceParser.yesNo("ok vai"))
        assertEquals(false, ChoiceParser.yesNo("no"))
        assertEquals(false, ChoiceParser.yesNo("no, annulla"))
        assertEquals(null, ChoiceParser.yesNo("chiama mario bianchi sul cellulare"))
        assertEquals(null, ChoiceParser.yesNo("boh"))
    }

    @Test fun sceltaSpotify() {
        val t = SpotifyItem.Kind.TRACK
        val tracks = listOf(SpotifyItem(t, "Albachiara", "spotify:track:1", "Vasco Rossi", "spotify:album:9"))
        val artists = listOf(SpotifyItem(SpotifyItem.Kind.ARTIST, "Vasco Rossi", "spotify:artist:2"))
        val albums = listOf(SpotifyItem(SpotifyItem.Kind.ALBUM, "Nevermind", "spotify:album:3"))
        val playlists = listOf(SpotifyItem(SpotifyItem.Kind.PLAYLIST, "Relax", "spotify:playlist:4"))
        fun pick(q: String, f: MediaFocus = MediaFocus.ANY) = SpotifyChooser.choose(q, f, tracks, artists, albums, playlists)?.uri
        assertEquals("spotify:artist:2", pick("vasco rossi"))
        assertEquals("spotify:track:1", pick("albachiara"))
        assertEquals("spotify:track:1", pick("albachiara di vasco rossi".replace(" di ", " ")))
        assertEquals("spotify:album:3", pick("nevermind"))
        assertEquals("spotify:playlist:4", pick("relax", MediaFocus.PLAYLIST))
        assertEquals("spotify:album:3", pick("qualunque", MediaFocus.ALBUM))
        assertEquals("Albachiara di Vasco Rossi", tracks[0].spoken())
    }

    @Test fun calendario() {
        // martedì 10 novembre 2026
        val oggi = java.time.LocalDate.of(2026, 11, 10)
        fun ev(t: String) = CalendarParser.parse(t, oggi)
        fun d(m: Int, g: Int, y: Int = 2026) = java.time.LocalDate.of(y, m, g)
        fun c(h: Int, m: Int = 0) = TimeParser.Clock(h, m)

        assertEquals(CalendarParser.EventSpec("Cena con Marco", d(11, 11), c(17)),
            ev("Aggiungi al calendario Cena con Marco per domani alle 17:00"))
        assertEquals(CalendarParser.EventSpec("Dentista", d(11, 11), c(17)),
            ev("aggiungi al calendario dentista domani alle 5 del pomeriggio"))
        assertEquals(CalendarParser.EventSpec("Riunione", d(11, 16), c(10, 30)),
            ev("crea un evento riunione lunedì alle 10 e mezza"))
        assertEquals(CalendarParser.EventSpec("Compleanno di Marco", d(11, 15), null),
            ev("segna in agenda il compleanno di Marco il 15 novembre"))
        assertEquals(CalendarParser.EventSpec("Visita medica", d(1, 3, 2027), c(9)),
            ev("aggiungi al calendario il 3 gennaio alle 9 visita medica"))
        assertEquals(CalendarParser.EventSpec("Palestra", d(11, 10), c(19)),
            ev("aggiungi al calendario palestra stasera alle 7"))
        assertEquals(CalendarParser.EventSpec("Partita di calcetto", d(11, 13), c(19), c(20, 30)),
            ev("aggiungi al calendario partita di calcetto venerdì dalle 19 alle 20 e 30"))
        assertEquals(CalendarParser.EventSpec("Chiamare l'idraulico", d(11, 13), null),
            ev("ricordami di chiamare l'idraulico tra 3 giorni"))
        assertEquals(CalendarParser.EventSpec("Aperitivo", null, c(19)),
            ev("aggiungi al calendario aperitivo alle 19"))
        assertEquals(CalendarParser.EventSpec("Riunione", d(11, 20), c(15)),
            ev("aggiungi al calendario riunione il 20 alle 15"))
        assertEquals(CalendarParser.EventSpec("Pranzo con mamma", d(11, 15), c(13)),
            ev("aggiungi al calendario pranzo con mamma il 15/11 all'una"))
        assertEquals(CalendarParser.EventSpec("Dentista", d(11, 16), c(9)),
            ev("aggiungi un appuntamento dal dentista lunedì prossimo alle 9 di mattina"))
        assertEquals(CalendarParser.EventSpec("Pranzo da Anna", d(11, 12), c(12, 30)),
            ev("Aggiungi pranzo da Anna al calendario giovedì alle 12:30"))
        assertEquals(CalendarParser.EventSpec("", d(11, 11), c(17)),
            ev("aggiungi al calendario domani alle 17"))
        assertEquals(CalendarParser.EventSpec("Dentista", null, null), ev("aggiungi al calendario dentista"))
        assertEquals("domani", CalendarParser.describe(d(11, 11), oggi))
        assertEquals("lunedì 16 novembre", CalendarParser.describe(d(11, 16), oggi))
        assertEquals("domenica 3 gennaio 2027", CalendarParser.describe(d(1, 3, 2027), oggi))
    }

    @Test fun comandoCalendario() {
        assertEquals(Command.AddEvent, CommandParser.parse("Aggiungi al calendario Cena con Marco per domani alle 17:00"))
        assertEquals(Command.AddEvent, CommandParser.parse("metti in agenda riunione lunedì"))
        assertEquals(Command.AddEvent, CommandParser.parse("crea un evento dentista"))
        assertEquals(Command.AddEvent, CommandParser.parse("aggiungi un appuntamento domani"))
        assertEquals(Command.AddEvent, CommandParser.parse("ricordami di chiamare Marco domani alle 5"))
        assertEquals(Command.Timer(600), CommandParser.parse("ricordami tra 10 minuti"))
        // non devono diventare eventi
        assertEquals(Command.Alarm(7, 0), CommandParser.parse("metti la sveglia alle 7"))
        assertEquals(Command.Play(PlayRequest("vasco rossi", MediaFocus.ANY)), CommandParser.parse("metti vasco rossi"))
    }

    @Test fun navigazione() {
        assertEquals(Command.Navigate("catania"), CommandParser.parse("portami a catania"))
        assertEquals(Command.Navigate("via etnea 120 catania"), CommandParser.parse("naviga verso via etnea cento venti catania"))
        assertEquals(Command.Navigate("via etnea 120"), CommandParser.parse("portami fino a via etnea centoventi"))
        assertEquals(Command.Navigate("l'aeroporto"), CommandParser.parse("andiamo verso l'aeroporto"))
        assertEquals(Command.Navigate("casa"), CommandParser.parse("portami a casa"))
        assertEquals(Command.Navigate("palermo"), CommandParser.parse("avvia la navigazione per palermo"))
        assertEquals(Command.Navigate("la farmacia più vicina"), CommandParser.parse("indicazioni per la farmacia più vicina"))
        assertEquals(Command.Navigate("via dei mille 4"), CommandParser.parse("voglio andare in via dei mille quattro"))
        assertEquals(Command.NavigateWhere, CommandParser.parse("portami"))
        assertEquals(Command.NavigateWhere, CommandParser.parse("avvia la navigazione"))
    }

    @Test fun annullaESconosciuti() {
        assertEquals(Command.Cancel, CommandParser.parse("annulla"))
        assertEquals(Command.Cancel, CommandParser.parse("lascia stare"))
        assertTrue(CommandParser.parse("che tempo fa domani") is Command.Unknown)
        assertTrue(CommandParser.parse("") is Command.Unknown)
        // "chiamata" non è il verbo "chiama"
        assertTrue(CommandParser.parse("chiamata persa") is Command.Unknown)
    }

    // ---------- numeri ----------

    @Test fun numeriInLettere() {
        assertEquals(23, ItalianNumbers.wordToInt("ventitré"))
        assertEquals(21, ItalianNumbers.wordToInt("ventuno"))
        assertEquals(38, ItalianNumbers.wordToInt("trentotto"))
        assertEquals(120, ItalianNumbers.wordToInt("centoventi"))
        assertEquals(180, ItalianNumbers.wordToInt("centottanta"))
        assertEquals(345, ItalianNumbers.wordToInt("trecentoquarantacinque"))
        assertEquals(2000, ItalianNumbers.wordToInt("duemila"))
        assertEquals(1500, ItalianNumbers.wordToInt("millecinquecento"))
        assertNull(ItalianNumbers.wordToInt("mario"))
        assertNull(ItalianNumbers.wordToInt("una"))
    }

    @Test fun numeriDiTelefono() {
        assertEquals("3331234567", ItalianNumbers.phoneDigits("tre tre tre uno due tre quattro cinque sei sette"))
        assertEquals("3331234", ItalianNumbers.phoneDigits("tre tre tre dodici trentaquattro"))
        assertEquals("+39095123", ItalianNumbers.phoneDigits("più trentanove zero novantacinque uno due tre"))
        assertNull(ItalianNumbers.phoneDigits("mario tre"))
        assertNull(ItalianNumbers.phoneDigits("sei")) // troppo corto: è un nome o un verbo, non un numero
    }

    // ---------- rubrica ----------

    private fun c(name: String) = Contact(name, listOf(PhoneNumber("1", "Cellulare", NumberKind.MOBILE)))
    private val rubrica = listOf(
        c("Mario Rossi"), c("Mario Bianchi"), c("Maria Bianchi"), c("Mamma"),
        c("Mamma Lavoro"), c("Giuseppe Verdi"), c("Dott. D'Angelo"), c("Luca"), c("Christian"),
    )

    @Test fun contattoUnico() {
        assertEquals(Match.One(rubrica[0]), ContactMatcher.find("mario rossi", rubrica))
        assertEquals(Match.One(rubrica[3]), ContactMatcher.find("mamma", rubrica))
        assertEquals(Match.One(rubrica[5]), ContactMatcher.find("giuseppe", rubrica))
        assertEquals(Match.One(rubrica[5]), ContactMatcher.find("giusepe", rubrica))
        assertEquals(Match.One(rubrica[8]), ContactMatcher.find("cristian", rubrica))
        // errore di riconoscimento di una lettera
        assertEquals(Match.One(rubrica[5]), ContactMatcher.find("giuseppe verde", rubrica))
        assertEquals(Match.One(rubrica[6]), ContactMatcher.find("d'angelo", rubrica))
    }

    @Test fun contattoAmbiguoOAssente() {
        val m = ContactMatcher.find("mario", rubrica)
        assertTrue(m is Match.Many)
        assertEquals(listOf("Mario Rossi", "Mario Bianchi"), (m as Match.Many).contacts.map { it.name })
        assertEquals(Match.None, ContactMatcher.find("francesca", rubrica))
        assertEquals(Match.None, ContactMatcher.find("", rubrica))
    }

    // ---------- risposte alle domande ----------

    @Test fun scelte() {
        val nomi = listOf("Mario Rossi", "Mario Bianchi")
        assertEquals(0, ChoiceParser.pick("rossi", nomi))
        assertEquals(1, ChoiceParser.pick("mario bianchi", nomi))
        assertEquals(1, ChoiceParser.pick("il secondo", nomi))
        assertEquals(1, ChoiceParser.pick("l'ultimo", nomi))
        assertNull(ChoiceParser.pick("mario", nomi))
        assertNull(ChoiceParser.pick("boh", nomi))

        val numeri = listOf("Cellulare", "Casa", "Lavoro")
        assertEquals(1, ChoiceParser.pick("quello di casa", numeri))
        assertEquals(0, ChoiceParser.pick("cellulare", numeri))
        assertEquals(2, ChoiceParser.pick("il terzo", numeri))
        assertEquals(NumberKind.WORK, ChoiceParser.kind("in ufficio"))
    }
}
