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
