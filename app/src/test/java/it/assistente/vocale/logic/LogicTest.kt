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
        c("Mamma Lavoro"), c("Giuseppe Verdi"), c("Dott. D'Angelo"), c("Luca"),
    )

    @Test fun contattoUnico() {
        assertEquals(Match.One(rubrica[0]), ContactMatcher.find("mario rossi", rubrica))
        assertEquals(Match.One(rubrica[3]), ContactMatcher.find("mamma", rubrica))
        assertEquals(Match.One(rubrica[5]), ContactMatcher.find("giuseppe", rubrica))
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
