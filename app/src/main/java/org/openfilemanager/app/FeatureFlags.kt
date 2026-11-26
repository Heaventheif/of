package org.openfilemanager.app

object FeatureFlags {
    @Volatile var recoveryCenter: Boolean = true

    @Volatile var settingsWriteStatus: Boolean = true

    @Volatile var archiveRejectionFeedback: Boolean = true

    @Volatile var operationHistory: Boolean = true

    @Volatile var perReaderPrefs: Boolean = true
}
