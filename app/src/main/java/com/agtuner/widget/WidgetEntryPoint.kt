package com.agtuner.widget

import com.agtuner.data.PreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point so the Glance widget — which isn't directly injectable —
 * can pull the singleton [PreferencesRepository] inside `provideGlance`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun preferencesRepository(): PreferencesRepository
}
