package dev.lmnaide.calendar.contacts

import org.junit.Assert.*
import org.junit.Test
import dev.lmnaide.calendar.domain.SmartEventParser
import dev.lmnaide.calendar.domain.SmartEventResult
import java.time.LocalDate

class ContactMatcherTest {
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
