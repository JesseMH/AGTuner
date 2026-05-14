package com.agtuner.tuning

import androidx.annotation.StringRes
import com.agtuner.R
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Represents a string with its note name and target frequency.
 *
 * `name` is the canonical ASCII form ("C", "C#", "Db") — stored in preferences and used
 * for preset matching. `displayName` renders Unicode ♯/♭ for the UI; `spokenName` spells
 * out the accidental ("C sharp 2") so TalkBack reads it correctly regardless of how the
 * screen reader handles ♯/♭ glyphs.
 */
data class StringNote(
    val name: String,
    val frequency: Float,
    val octave: Int
) {
    val displayName: String
        get() = name.replace("#", "♯").replace("b", "♭") + octave

    val spokenName: String
        get() {
            val letter = name.first()
            val accidental = when (name.getOrNull(1)) {
                '#' -> " sharp"
                'b' -> " flat"
                else -> ""
            }
            return "$letter$accidental $octave"
        }
}

/**
 * A named tuning preset (e.g. Standard, Drop D). The `id` is the persisted identifier;
 * label resources live alongside the preset so adding a new preset can't compile without
 * supplying its display strings.
 */
data class TuningPreset(
    val id: String,
    val strings: List<StringNote>,
    @StringRes val nameRes: Int,
    @StringRes val stringsRes: Int,
    @StringRes val stringsSpokenRes: Int,
)

/**
 * Visual + spoken label pair for a note. Carrying both as a single value forces them
 * to be updated together; without this, the pipeline + UI both grew defensive
 * "fall back to display if spoken is missing" workarounds.
 */
data class NoteLabel(val display: String, val spoken: String) {
    companion object {
        val Empty = NoteLabel("", "")
    }
}

/** Render a [StringNote] as its visual + spoken label pair. */
fun StringNote.toLabel(): NoteLabel = NoteLabel(displayName, spokenName)

/**
 * Contains note frequencies and standard tuning presets.
 */
object NoteFrequencies {

    // Chromatic note names. Two parallel tables for enharmonic spelling: sharps for tunings
    // built from naturals/sharps (Standard, Drop D, Drop C#), flats for flat-leaning tunings
    // (Half-step down, Drop Db). The ASCII forms ("C#", "Db") are persisted; the UI layer
    // renders Unicode ♯/♭ via StringNote.displayName.
    private val NOTE_NAMES_SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val NOTE_NAMES_FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    // A4 = 440 Hz as reference
    private const val A4_FREQUENCY = 440f
    private const val A4_MIDI_NUMBER = 69

    // Practical string-instrument range (covers C1 ≈ 32.7 Hz up to F5 ≈ 698.5 Hz).
    private const val PRACTICAL_RANGE_MIN_HZ = 30f
    private const val PRACTICAL_RANGE_MAX_HZ = 700f

    // MIDI note constants for tuning presets. Single source of truth — frequencies
    // and display names are derived via fromMidi() to avoid hand-rounded float literals.
    // Reference: C-1 = 0, A4 = 69. So MIDI = (octave + 1) * 12 + noteIndex.
    private const val MIDI_B0 = 23
    private const val MIDI_E1 = 28
    private const val MIDI_F_SHARP_1 = 30
    private const val MIDI_A1 = 33
    private const val MIDI_B1 = 35
    private const val MIDI_D2 = 38
    private const val MIDI_E_FLAT_2 = 39
    private const val MIDI_E2 = 40
    private const val MIDI_G2 = 43
    private const val MIDI_A_FLAT_2 = 44
    private const val MIDI_A2 = 45
    private const val MIDI_D_FLAT_3 = 49
    private const val MIDI_D3 = 50
    private const val MIDI_G_FLAT_3 = 54
    private const val MIDI_G3 = 55
    private const val MIDI_B_FLAT_3 = 58
    private const val MIDI_B3 = 59
    private const val MIDI_E_FLAT_4 = 63
    private const val MIDI_E4 = 64

    /**
     * Build a StringNote from a MIDI number. Name, octave, and frequency are all derived
     * — no rounding error from hand-typed float literals. [preferFlats] picks the flat
     * spelling for accidentals (Eb instead of D#); naturals are unaffected.
     */
    private fun fromMidi(midi: Int, preferFlats: Boolean = false): StringNote {
        val octave = (midi / 12) - 1
        val noteIndex = midi % 12
        val table = if (preferFlats) NOTE_NAMES_FLAT else NOTE_NAMES_SHARP
        return StringNote(table[noteIndex], midiToFrequency(midi), octave)
    }

    /**
     * Standard 6-string guitar tuning (E2-A2-D3-G3-B3-E4).
     */
    val STANDARD_GUITAR = listOf(
        fromMidi(MIDI_E2),
        fromMidi(MIDI_A2),
        fromMidi(MIDI_D3),
        fromMidi(MIDI_G3),
        fromMidi(MIDI_B3),
        fromMidi(MIDI_E4)
    )

    /** Stable preset ids — persisted in preferences. Don't rename. */
    const val PRESET_STANDARD = "standard"
    const val PRESET_HALF_STEP_DOWN = "half_step_down"
    const val PRESET_DROP_D = "drop_d"

    /**
     * Built-in tuning presets, ordered by expected frequency of use.
     * The "Custom" state is represented by a null preset id, not an entry here.
     */
    val PRESETS: List<TuningPreset> = listOf(
        TuningPreset(
            id = PRESET_STANDARD,
            strings = STANDARD_GUITAR,
            nameRes = R.string.tuning_preset_standard,
            stringsRes = R.string.tuning_preset_standard_strings,
            stringsSpokenRes = R.string.tuning_preset_standard_strings_spoken,
        ),
        TuningPreset(
            id = PRESET_HALF_STEP_DOWN,
            strings = listOf(
                fromMidi(MIDI_E_FLAT_2, preferFlats = true),
                fromMidi(MIDI_A_FLAT_2, preferFlats = true),
                fromMidi(MIDI_D_FLAT_3, preferFlats = true),
                fromMidi(MIDI_G_FLAT_3, preferFlats = true),
                fromMidi(MIDI_B_FLAT_3, preferFlats = true),
                fromMidi(MIDI_E_FLAT_4, preferFlats = true)
            ),
            nameRes = R.string.tuning_preset_half_step_down,
            stringsRes = R.string.tuning_preset_half_step_down_strings,
            stringsSpokenRes = R.string.tuning_preset_half_step_down_strings_spoken,
        ),
        TuningPreset(
            id = PRESET_DROP_D,
            strings = listOf(
                fromMidi(MIDI_D2),
                fromMidi(MIDI_A2),
                fromMidi(MIDI_D3),
                fromMidi(MIDI_G3),
                fromMidi(MIDI_B3),
                fromMidi(MIDI_E4)
            ),
            nameRes = R.string.tuning_preset_drop_d,
            stringsRes = R.string.tuning_preset_drop_d_strings,
            stringsSpokenRes = R.string.tuning_preset_drop_d_strings_spoken,
        )
    )

    /**
     * Returns the preset whose strings match [config] exactly (by name + octave),
     * or null if no preset matches — i.e. the user has a custom tuning.
     */
    fun findMatchingPreset(config: List<StringNote>): TuningPreset? {
        return PRESETS.firstOrNull { preset ->
            preset.strings.size == config.size &&
                preset.strings.zip(config).all { (a, b) -> a.name == b.name && a.octave == b.octave }
        }
    }

    /**
     * Get all available notes in the practical string-instrument range (C1 to F5,
     * ~30Hz to ~700Hz).
     */
    fun getAllNotes(): List<StringNote> {
        val notes = mutableListOf<StringNote>()

        for (octave in 1..5) {
            for (noteIndex in 0 until 12) {
                val midiNumber = (octave + 1) * 12 + noteIndex
                val note = fromMidi(midiNumber)

                if (note.frequency in PRACTICAL_RANGE_MIN_HZ..PRACTICAL_RANGE_MAX_HZ) {
                    notes.add(note)
                }
            }
        }

        return notes
    }

    /**
     * Find the closest note to a given frequency. [preferFlats] picks flat spellings
     * for accidentals so detected-note display matches the active tuning's convention.
     *
     * Falls back to A4 for non-positive or non-finite input — `frequencyToMidi` would
     * otherwise produce -∞/NaN, and `roundToInt()` either throws (NaN) or yields
     * Int.MIN_VALUE (-∞), which then crashes `fromMidi` on negative `midi % 12`.
     */
    fun findClosestNote(frequency: Float, preferFlats: Boolean = false): StringNote {
        if (frequency <= 0f || !frequency.isFinite()) {
            return fromMidi(A4_MIDI_NUMBER, preferFlats)
        }
        val midiNumber = frequencyToMidi(frequency)
        return fromMidi(midiNumber.roundToInt(), preferFlats)
    }

    /**
     * Convert MIDI note number to frequency.
     */
    fun midiToFrequency(midiNumber: Int): Float {
        return A4_FREQUENCY * 2f.pow((midiNumber - A4_MIDI_NUMBER) / 12f)
    }

    /**
     * Convert frequency to MIDI note number.
     */
    fun frequencyToMidi(frequency: Float): Float {
        return A4_MIDI_NUMBER + 12f * kotlin.math.log2(frequency / A4_FREQUENCY)
    }

    /**
     * Create a default tuning with the specified number of strings.
     * Distributes notes evenly across a reasonable range.
     */
    fun createDefaultTuning(stringCount: Int): List<StringNote> {
        return when (stringCount) {
            4 -> listOf(  // Bass guitar
                fromMidi(MIDI_E1), fromMidi(MIDI_A1), fromMidi(MIDI_D2), fromMidi(MIDI_G2)
            )
            5 -> listOf(  // 5-string bass
                fromMidi(MIDI_B0), fromMidi(MIDI_E1), fromMidi(MIDI_A1),
                fromMidi(MIDI_D2), fromMidi(MIDI_G2)
            )
            6 -> STANDARD_GUITAR
            7 -> listOf(  // 7-string guitar
                fromMidi(MIDI_B1), fromMidi(MIDI_E2), fromMidi(MIDI_A2),
                fromMidi(MIDI_D3), fromMidi(MIDI_G3), fromMidi(MIDI_B3), fromMidi(MIDI_E4)
            )
            8 -> listOf(  // 8-string guitar
                fromMidi(MIDI_F_SHARP_1), fromMidi(MIDI_B1), fromMidi(MIDI_E2), fromMidi(MIDI_A2),
                fromMidi(MIDI_D3), fromMidi(MIDI_G3), fromMidi(MIDI_B3), fromMidi(MIDI_E4)
            )
            else -> {
                if (stringCount < 6) {
                    STANDARD_GUITAR.take(stringCount)
                } else {
                    // Beyond 8 strings, add successive higher octaves of E
                    STANDARD_GUITAR + List(stringCount - 6) { i ->
                        fromMidi(MIDI_E4 + 12 * (i + 1))
                    }
                }
            }
        }
    }
}
