package com.rendyhd.vicu.data.local

import androidx.room.RoomDatabase

expect class PlatformContext

expect fun getDatabaseBuilder(ctx: PlatformContext): RoomDatabase.Builder<VikunjaDatabase>
