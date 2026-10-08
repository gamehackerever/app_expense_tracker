package com.expensetracker.offline.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// New File: SquadEntity.kt
@Entity(tableName = "squads")
data class SquadEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String, // e.g., "Kattangal Crew", "Ooty Trip"
    val defaultPayer: String = "You",
    val participantsJson: String // Serialized List of names for one-tap loading
)