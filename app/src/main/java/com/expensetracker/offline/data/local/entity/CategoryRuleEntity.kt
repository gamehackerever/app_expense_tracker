package com.expensetracker.offline.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category_rules",
    indices = [Index(value = ["merchantPattern"], unique = true)]
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchantPattern: String, // Normalized lowercase merchant/payee keyword
    val category: String,
    val usageCount: Int = 1,     // Increments as you reinforce the pattern
    val lastUpdated: Long = System.currentTimeMillis()
)