package com.agtuner.feedback

/**
 * User preferences for feedback types.
 */
data class FeedbackPreferences(
    val audioEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val voiceOnInTuneOnly: Boolean = false // Announce voice for all states by default (accessibility)
)
