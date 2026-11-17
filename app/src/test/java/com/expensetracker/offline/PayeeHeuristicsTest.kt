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

    @Test fun smartLayer_noteWithPuff_classifiedAsFoodAndDining() {
        // Even when paying an individual person ("Ramesh Kumar"), note "puff" makes it Food & Dining!
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Ramesh Kumar", "UPI/412345/Ramesh", note = "puff"))
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Suresh", "Paid to Suresh", note = "egg puff and chai"))
        assertEquals("Food & Dining", CategoryClassifier.classifySync("UPI Payment", "Paid 50", note = "veg puff"))
    }

    @Test fun smartLayer_noteClassifiesVariousExpenseCategories() {
        assertEquals("Fuel & Fastag", CategoryClassifier.classifySync("UPI Payment", "Paid 200", note = "petrol"))
        assertEquals("Personal Care", CategoryClassifier.classifySync("Anil Kumar", "Paid to Anil", note = "hair cut"))
        assertEquals("Health", CategoryClassifier.classifySync("Mohan", "Paid to Mohan", note = "paracetamol medicine"))
        assertEquals("Groceries", CategoryClassifier.classifySync("Kirana Vendor", "Paid 500", note = "groceries and milk"))
        assertEquals("Recharge & Telecom", CategoryClassifier.classifySync("UPI Payment", "Paid 299", note = "mobile recharge"))
        assertEquals("Rent & Housing", CategoryClassifier.classifySync("Landlord Sharma", "Paid 15000", note = "house rent"))
        assertEquals("Education", CategoryClassifier.classifySync("Ravi Sir", "Paid 2000", note = "tuition fees"))
        assertEquals("Entertainment", CategoryClassifier.classifySync("Deepak", "Paid 400", note = "movie tickets"))
    }

    @Test fun smartLayer_payeeWithPuffEntity_classifiedAsFoodAndDining() {
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Puff Corner", "UPI/123/PUFF_CORNER/CNRB"))
        assertEquals("Food & Dining", CategoryClassifier.classifySync("Bangalore Puffs & Bakes", "Bangalore Puffs & Bakes"))
    }

    @Test fun smartLayer_unclassifiablePersonWithoutNote_staysUncategorized() {
        assertEquals("Uncategorized", CategoryClassifier.classifySync("Ramesh Kumar", "UPI/412345/Ramesh/CNRB", note = null))
    }
}
