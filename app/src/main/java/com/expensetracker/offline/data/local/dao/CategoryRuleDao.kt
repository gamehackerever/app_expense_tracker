package com.expensetracker.offline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.expensetracker.offline.data.local.entity.CategoryRuleEntity

@Dao
interface CategoryRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: CategoryRuleEntity)

    @Query("SELECT * FROM category_rules WHERE merchantPattern = :pattern LIMIT 1")
    suspend fun getExactRule(pattern: String): CategoryRuleEntity?

    @Query("SELECT * FROM category_rules ORDER BY usageCount DESC")
    suspend fun getAllRules(): List<CategoryRuleEntity>

    // BUG FIX: was ordered by usageCount DESC only, so a short, generic learned
    // pattern (e.g. "pay") with a high usage count could win over a longer,
    // more specific pattern (e.g. "amazon pay") that's a better match for the
    // actual payee — miscategorizing merchants that contain a common word.
    // Prefer the longest (most specific) matching pattern first; usageCount is
    // now only a tiebreaker between equally-specific patterns.
    @Query("SELECT category FROM category_rules WHERE :query LIKE '%' || merchantPattern || '%' ESCAPE '\\' ORDER BY LENGTH(merchantPattern) DESC, usageCount DESC LIMIT 1")
    suspend fun findMatchingCategory(query: String): String?

    @Query("UPDATE category_rules SET category = :newCategory WHERE category = :oldCategory")
    suspend fun reassignRuleCategory(oldCategory: String, newCategory: String)
}