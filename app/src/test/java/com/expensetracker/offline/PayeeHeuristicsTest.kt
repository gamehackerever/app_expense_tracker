package com.expensetracker.offline

import com.expensetracker.offline.engine.categorizer.CategoryClassifier
import com.expensetracker.offline.engine.categorizer.PayeeHeuristics
import org.junit.Assert.*
import org.junit.Test

class PayeeHeuristicsTest {
    @Test fun spellingVariantsReachFoodRules() {
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Ultra Biriyani paradise", "Ultra Biriyani paradise"))
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Cofee Shop", "Cofee Shop"))
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Jk Foods", "Jk Foods"))
    }

    @Test fun shopTypeWords() {
        assertEquals("Groceries", CategoryClassifier.classifySync("M A Super Market", "M A Super Market"))
        assertEquals("Groceries", CategoryClassifier.classifySync("Mahalakshmi Stores", "Mahalakshmi Stores"))
        assertEquals("Health", CategoryClassifier.classifySync("City Medicals", "City Medicals"))
        assertEquals("Bills & Utilities", CategoryClassifier.classifySync("Uidai", "Uidai"))
    }

    @Test fun peopleStayUncategorizedButAreRecognised() {
        for (n in listOf("Vijeesh V", "Roshith T", "Jeeshma M M", "Hemavathi M", "vybhav Sukumar")) {
            assertEquals("Uncategorized", CategoryClassifier.classifySync(n, n))
            assertTrue(n, PayeeHeuristics.looksLikePerson(n))
        }
        for (n in listOf("M A Super Market", "Jk Foods", "Naas mobiles", "Cofee Shop", "Sarovar 92001")) {
            assertFalse(n, PayeeHeuristics.looksLikePerson(n))
        }
    }
}
