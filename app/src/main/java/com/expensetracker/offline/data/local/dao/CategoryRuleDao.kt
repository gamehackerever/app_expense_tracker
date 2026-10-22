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

    // Prefer the longest matching pattern first (ignoring short <3 char patterns);
    // usageCount is a tiebreaker between equally-specific patterns.
    @Query("SELECT category FROM category_rules WHERE LENGTH(TRIM(merchantPattern)) >= 3 AND :query LIKE '%' || merchantPattern || '%' ESCAPE '\\' ORDER BY LENGTH(merchantPattern) DESC, usageCount DESC LIMIT 1")
    suspend fun findMatchingCategory(query: String): String?

    @Query("UPDATE category_rules SET category = :newCategory WHERE category = :oldCategory")
    suspend fun reassignRuleCategory(oldCategory: String, newCategory: String)

    @Query("DELETE FROM category_rules WHERE merchantPattern = :pattern")
    suspend fun deleteRuleByPattern(pattern: String)

    @Query("DELETE FROM category_rules WHERE LOWER(TRIM(merchantPattern)) IN (:patterns) OR LENGTH(TRIM(merchantPattern)) < 3")
    suspend fun deleteGenericOrShortRules(patterns: List<String>)
}