package com.rendyhd.vicu.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `routine_occurrence_archive` (
                `routineId` TEXT NOT NULL,
                `occurrenceKey` TEXT NOT NULL,
                `slotId` TEXT NOT NULL,
                `scheduledDate` TEXT NOT NULL,
                `scheduledMinutes` INTEGER NOT NULL,
                `timeZoneId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `loggedAt` TEXT NOT NULL,
                `modifiedAt` TEXT NOT NULL,
                `modifiedBy` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                PRIMARY KEY(`routineId`, `occurrenceKey`)
            )
            """.trimIndent(),
        )
    }
}
