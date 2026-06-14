package com.rendyhd.vicu.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSFileManager

actual class PlatformContext

actual fun getDatabaseBuilder(ctx: PlatformContext): RoomDatabase.Builder<VikunjaDatabase> {
    val fm = NSFileManager.defaultManager
    val groupUrl = fm.containerURLForSecurityApplicationGroupIdentifier("group.com.rendyhd.vicu")
    val dbFilePath = if (groupUrl != null) {
        groupUrl.path + "/vicu_database.db"
    } else {
        NSHomeDirectory() + "/Documents/vicu_database.db"
    }

    return Room.databaseBuilder<VikunjaDatabase>(
        name = dbFilePath ?: (NSHomeDirectory() + "/Documents/vicu_database.db"),
        factory = { VikunjaDatabaseConstructor.initialize() }
    ).setDriver(BundledSQLiteDriver())
}
