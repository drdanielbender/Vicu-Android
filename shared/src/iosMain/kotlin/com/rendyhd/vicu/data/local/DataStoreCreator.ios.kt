package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

actual fun createDataStore(context: PlatformContext, name: String): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val fm = NSFileManager.defaultManager
            val groupUrl = fm.containerURLForSecurityApplicationGroupIdentifier("group.com.rendyhd.vicu")
            val basePath = if (groupUrl != null) {
                groupUrl.path
            } else {
                val documentDirectory = fm.URLForDirectory(
                    NSDocumentDirectory, NSUserDomainMask, null, true, null
                )
                documentDirectory?.path
            }
            "$basePath/datastore_$name.preferences_pb".toPath()
        }
    )
}
