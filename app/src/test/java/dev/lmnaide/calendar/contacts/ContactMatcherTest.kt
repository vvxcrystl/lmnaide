package dev.lmnaide.calendar.contacts

import org.junit.Assert.*
import org.junit.Test
import dev.lmnaide.calendar.domain.SmartEventParser
import dev.lmnaide.calendar.domain.SmartEventResult
import java.time.LocalDate

class ContactMatcherTest {
    @Test fun wordIndexKeepsOrderAndRequiresConsecutivePartsWithoutDuplicateMatches() {
        val contacts = listOf(EventPerson("Alex Alex Smith", "1"), EventPerson("Alex Lee Smith", "2"), EventPerson("Dr Alex Smith", "3"))
        val index = ContactMatcher.Index(contacts)
        assertEquals(contacts, index.matches("Alex"))
        assertEquals(listOf(contacts[0], contacts[2]), index.matches("Alex Smith"))
        assertTrue(index.matches("Smith Alex").isEmpty())
        assertEquals(listOf(contacts[0]), index.matches("Alex Alex"))
    }

    @Test fun reusableIndexPreservesAccentsPunctuationAmbiguityAndExactPreference() {
        val contacts = listOf(
            EventPerson("João D’Angelo", "1"), EventPerson("João Smith", "2"),
            EventPerson("Mary-Jane Watson", "3"), EventPerson("João", "4"),
        )
        val index = ContactMatcher.Index(contacts)
        assertEquals(listOf(contacts[0]), index.matches("d'angelo"))
        assertEquals(listOf(contacts[2]), index.matches("MARY-JANE"))
        assertEquals(listOf(contacts[3]), index.matches("João"))
        assertEquals(listOf(contacts[0]), index.matches("João D'Angelo"))
        assertTrue(index.matches("João D'Angelo Extra").isEmpty())
        assertTrue(index.matches("Mary").isEmpty())
        assertTrue(index.matches("...").isEmpty())
        val ambiguous = ContactMatcher.Index(contacts.dropLast(1))
        assertEquals(contacts.take(2), ambiguous.matches("João"))
    }

    @Test fun smartParserMatchesFirstOrLastNameToFullContact() {
        val sam = EventPerson("Sam Altman", "1")
        listOf("Sam", "Altman", "sam", "ALTMAN").forEach { name ->
            val draft = (SmartEventParser.parse("Dinner with $name tomorrow at 7pm", LocalDate.of(2026, 10, 10)) as SmartEventResult.Success).event
            assertEquals(listOf(sam), ContactMatcher.names(draft.title).flatMap { ContactMatcher.matches(it, listOf(sam)) })
        }
    }

    @Test fun matchesWholeNamePartsDespiteDisplayPunctuationAndSpacing() {
        val sam = EventPerson("Altman,  Sam (Work)", "1")
        val mary = EventPerson("Mary Jane Watson", "2")
        assertEquals(listOf(sam), ContactMatcher.matches(" Altman ", listOf(sam)))
        assertEquals(listOf(sam), ContactMatcher.matches("Sam", listOf(sam)))
        assertEquals(listOf(mary), ContactMatcher.matches("mary  jane", listOf(mary)))
        assertTrue(ContactMatcher.matches("Al", listOf(sam)).isEmpty())
        assertTrue(ContactMatcher.matches(" ", listOf(sam)).isEmpty())
        assertTrue(ContactMatcher.matches("...", listOf(sam)).isEmpty())
    }

    @Test fun exactNamesWinOverPartialNamesAndLastNamesCanBeAmbiguous() {
        val contacts = listOf(EventPerson("Sam", "1"), EventPerson("Sam Altman", "2"), EventPerson("Alex Altman", "3"))
        assertEquals(listOf(contacts[0]), ContactMatcher.matches("Sam", contacts))
        assertEquals(contacts.drop(1), ContactMatcher.matches("Altman", contacts))
    }

    @Test fun exampleExtractsOnlyOtherPeople() {
        val draft = (SmartEventParser.parse("Movie Night with Sam, Dario, Altman and I on the 10th at 7pm", LocalDate.of(2026, 10, 10)) as SmartEventResult.Success).event
        assertEquals(listOf("Sam", "Dario", "Altman"), ContactMatcher.names(draft.title))
        assertEquals(19, draft.start.hour)
        assertEquals(LocalDate.of(2026, 10, 10), draft.start.toLocalDate())
    }
    @Test fun exactNamesWinAndAmbiguousNamesNeedChoice() {
        val contacts = listOf(EventPerson("Sam Jones", "1"), EventPerson("Sam Smith", "2"), EventPerson("Dario", "3"))
        assertEquals(2, ContactMatcher.matches("Sam", contacts).size)
        assertEquals(listOf(contacts[0]), ContactMatcher.matches("sam jones", contacts))
        assertTrue(ContactMatcher.matches("Samantha", contacts).isEmpty())
        assertEquals(listOf(contacts[2]), ContactMatcher.matches("dario", contacts))
        assertTrue(ContactMatcher.names("Movie night").isEmpty())
    }
}
