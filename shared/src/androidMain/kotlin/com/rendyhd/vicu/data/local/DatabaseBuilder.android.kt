package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

actual class PlatformContext(val context: Context)

actual fun getDatabaseBuilder(ctx: PlatformContext): RoomDatabase.Builder<VikunjaDatabase> {
    val appContext = ctx.context.applicationContext
    val dbFile = appContext.getDatabasePath("vicu_database")
    return Room.databaseBuilder<VikunjaDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}
