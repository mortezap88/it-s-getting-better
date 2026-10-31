package com.example.notepad.data.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.audio.AudioTranscoder
import com.example.network.AvalAiClient
import com.example.network.CorrectionJsonCodec
import com.example.network.LinguisticCorrection
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class VoiceNoteAnalysis(
    val transcription: String,
    val englishTranslation: String? = null,
    val corrections: List<LinguisticCorrection> = emptyList()
)

/**
 * Transcribes and analyzes Notepad voice memos using:
 * 1. **Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`)** from 1xAi (`1xai.ir/v1`) with minimal/none thinking for ultra-fast STT and linguistic analysis.
 * 2. **GPT 6 Luna (`code/gpt-6-luna`)** reserved only for main answers and generative text.
 */
class GeminiService {

    companion object {
        const val TRANSCRIPTION_MODEL = AppSettingsRepository.GEMINI_STT_MODEL
        const val ANALYSIS_MODEL = AppSettingsRepository.GEMINI_STT_MODEL
        const val SILENCE_TRANSCRIPTION = "Silence: no speech was audible."

        private val PERSIAN_ARABIC_SCRIPT_REGEX =
            Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
        private val LATIN_LETTER_REGEX = Regex("[A-Za-z]")
        private val PATCH_MARKER_REGEX = Regex("(?i)\\{patch\\s*\\d+\\}|\\[patch\\s*\\d+\\]")
        private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

        internal fun containsPersianScript(text: String): Boolean =
            PERSIAN_ARABIC_SCRIPT_REGEX.containsMatchIn(text)

        fun stripPatchMarkers(text: String): String {
            if (text.isBlank()) return text
            return text
                .replace(PATCH_MARKER_REGEX, "")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }
    }

    private val avalAiClient = AvalAiClient()

    suspend fun analyzeVoiceNote(
        audioFile: File,
        avalaiKeyCandidates: List<Pair<Int, String>>,
        onAvalaiKeySucceeded: (Int) -> Unit = {}
    ): Result<VoiceNoteAnalysis> = withContext(Dispatchers.IO) {
        val resolvedKeys = avalaiKeyCandidates
            .map { (idx, key) -> idx to key.trim() }
            .filter { (_, key) -> key.isNotBlank() }
            .ifEmpty {
                val onexaiFallback = runCatching {
                    val f = BuildConfig::class.java.getField("ONEXAI_API_KEY")
                    (f.get(null) as? String)?.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() && it != "MY_ONEXAI_API_KEY" }

                val selected = onexaiFallback ?: AppSettingsRepository.DEFAULT_1XAI_KEY
                listOf(0 to selected)
            }

        if (resolvedKeys.isEmpty()) {
            return@withContext Result.failure(
                VoiceNoteApiException(AvalAiClient.MISSING_AVALAI_KEY_MESSAGE)
            )
        }

        val audioBytes = try {
            audioFile.readBytes()
        } catch (e: IOException) {
            Log.e("VoiceNoteService", "Could not read recorded audio", e)
            return@withContext Result.failure(
                AudioFileException("Audio file issue: could not read the recording.")
            )
        }
        if (audioBytes.isEmpty()) {
            return@withContext Result.failure(
                AudioFileException("Audio file issue: the recording is empty.")
            )
        }

        analyzeVoiceNotePatch(
            audioBytes = audioBytes,
            recentContext = "",
            patchNumber = 1,
            avalaiKeyCandidates = avalaiKeyCandidates,
            onAvalaiKeySucceeded = onAvalaiKeySucceeded
        )
    }

    suspend fun analyzeVoiceNotePatch(
        audioBytes: ByteArray,
        recentContext: String = "",
        patchNumber: Int = 1,
        avalaiKeyCandidates: List<Pair<Int, String>>,
        onAvalaiKeySucceeded: (Int) -> Unit = {}
    ): Result<VoiceNoteAnalysis> = withContext(Dispatchers.IO) {
        val resolvedKeys = avalaiKeyCandidates
            .map { (idx, key) -> idx to key.trim() }
            .filter { (_, key) -> key.isNotBlank() }
            .ifEmpty {
                val onexaiFallback = runCatching {
                    val f = BuildConfig::class.java.getField("ONEXAI_API_KEY")
                    (f.get(null) as? String)?.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() && it != "MY_ONEXAI_API_KEY" }

                val selected = onexaiFallback ?: AppSettingsRepository.DEFAULT_1XAI_KEY
                listOf(0 to selected)
            }

        if (resolvedKeys.isEmpty()) {
            return@withContext Result.failure(
                VoiceNoteApiException(AvalAiClient.MISSING_AVALAI_KEY_MESSAGE)
            )
        }

        if (audioBytes.isEmpty()) {
            return@withContext Result.failure(
                AudioFileException("Audio patch is empty.")
            )
        }

        val wavBytes = AudioTranscoder.ensureWavBytes(audioBytes, null)
        if (wavBytes.isEmpty()) {
            return@withContext Result.failure(
                AudioFileException("Audio patch could not be prepared.")
            )
        }

        val sttResult = avalAiClient.transcribeAndAnalyzeNotepadVoiceNote(
            audioBytes = wavBytes,
            mimeType = "audio/wav",
            apiKeyCandidates = resolvedKeys,
            recentContext = recentContext,
            patchNumber = patchNumber,
            model = TRANSCRIPTION_MODEL,
            onKeySucceeded = onAvalaiKeySucceeded
        )
        if (sttResult.isFailure) {
            val err = sttResult.exceptionOrNull()
            return@withContext Result.failure(
                VoiceNoteApiException(err?.localizedMessage ?: "Audio patch transcription failed.")
            )
        }

        val rawText = sttResult.getOrNull()?.trim().orEmpty()
        val parsed = parseVoiceNoteAnalysis(rawText)
        Result.success(parsed)
    }

    internal fun buildVoiceNoteAnalysisMessages(transcript: String): List<AvalAiClient.Message> {
        val systemPrompt = """
            You review raw Speech-to-Text (STT) transcriptions of spontaneous spoken voice notes in English or Persian (Farsi).
            Always output in EXACTLY these two sections in order, with no extra commentary before or after:
            [translation]
            <ONLY if the spoken transcription is in Persian (Farsi), write a natural, accurate English translation of what was said. If the spoken transcription is in English (or not Persian), write NONE. NEVER translate English into Persian.>
            [correction]
            <A JSON array of ONLY clear, substantive spoken corrections or phrasing improvements: [{"type":"GRAMMAR"|"WORD_CHOICE","original":"exact contiguous substring from transcription","suggested":"corrected or improved words in the same language","reason":"concise explanation in English"}].
            CRITICAL — STT & SPOKEN SPEECH RULES (DO NOT BE OVERLY SENSITIVE):
            - This text comes from automatic Speech-to-Text (STT) of casual speech. Missing or imperfect punctuation marks (., ,, ;, :, ?, !, ،, ؛, ؟, ', ", -), unseparated run-on clauses, capitalization, apostrophes, and Persian half-spaces (ZWNJ / نیم‌فاصله) are 100% normal STT output — NEVER flag them and NEVER add punctuation marks in "suggested".
            - Natural spoken/colloquial language is completely valid: filler words ("خب", "امم", "well", "uh"), contractions ("I'm", "gonna", "wanna"), informal Persian ("یه", "خونه", "می‌رم", "نداره"), spoken ellipsis, and minor STT phonetic drops (such as dropping an unstressed colloquial Persian "-ه" copula like "این یه تست" for "این یه تسته") are normal — NEVER flag them, and do NOT convert casual speech into formal written prose.
            - Use "GRAMMAR" ONLY for clear spoken grammar mistakes that sound genuinely wrong out loud (e.g., wrong verb tense, wrong subject-verb agreement, wrong preposition, broken word order).
            - Use "WORD_CHOICE" ONLY when a word or phrase sounds noticeably unnatural, awkward, or unidiomatic in everyday conversation and has a clearly more natural spoken alternative.
            - Keep each "original" short (1 to 6 words around the specific error). If the utterance already sounds natural in everyday speech, return []. When in doubt, return [].>
        """.trimIndent()

        val userPrompt = buildString {
            append("Analyze this raw Speech-to-Text voice note transcription (ignore punctuation/marks and normal STT/colloquial speech features):\n")
            append(transcript)
        }

        return listOf(
            AvalAiClient.Message(role = "system", content = systemPrompt),
            AvalAiClient.Message(role = "user", content = userPrompt)
        )
    }

    internal fun parseVoiceNoteAnalysis(rawText: String): VoiceNoteAnalysis {
        val cleaned = rawText.trim()
        if (
            cleaned.equals("NO_SPEECH", ignoreCase = true) ||
            cleaned.equals("SILENCE", ignoreCase = true) ||
            cleaned.equals(SILENCE_TRANSCRIPTION, ignoreCase = true)
        ) {
            return VoiceNoteAnalysis(transcription = SILENCE_TRANSCRIPTION)
        }

        val transcriptionMatch = Regex(
            "(?is)\\[transcription\\]\\s*(.*?)(?=\\[translation\\]|\\[correction\\]|\\[analysis\\]|\\[linguistic_correction\\]|$)"
        ).find(cleaned)
        val translationMatch = Regex(
            "(?is)\\[translation\\]\\s*(.*?)(?=\\[transcription\\]|\\[correction\\]|\\[analysis\\]|\\[linguistic_correction\\]|$)"
        ).find(cleaned)
        val correctionMatch = Regex(
            "(?is)\\[(?:correction|analysis|linguistic_correction)\\]\\s*(.*?)(?=\\[transcription\\]|\\[translation\\]|$)"
        ).find(cleaned)

        val extractedTranscription = transcriptionMatch?.groupValues?.getOrNull(1)?.trim()
            ?: cleaned
                .substringBefore("[translation]")
                .substringBefore("[correction]")
                .substringBefore("[analysis]")
                .substringBefore("[linguistic_correction]")
                .trim()

        val cleanTranscription = stripPatchMarkers(extractedTranscription)

        if (
            cleanTranscription.isBlank() ||
            cleanTranscription.equals("NO_SPEECH", ignoreCase = true) ||
            cleanTranscription.equals("SILENCE", ignoreCase = true) ||
            cleanTranscription.equals(SILENCE_TRANSCRIPTION, ignoreCase = true)
        ) {
            return VoiceNoteAnalysis(transcription = SILENCE_TRANSCRIPTION)
        }

        val rawTranslation = translationMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val cleanTranslation = stripPatchMarkers(rawTranslation)
        // Enforce one-way rule: ONLY Persian spoken notes receive an English translation.
        // English spoken notes must NEVER show a translation box.
        val spokenInPersian = containsPersianScript(cleanTranscription)
        val validEnglishTranslation = cleanTranslation
            .takeIf { candidate ->
                spokenInPersian &&
                    candidate.isNotBlank() &&
                    !candidate.equals("NONE", ignoreCase = true) &&
                    !candidate.equals("N/A", ignoreCase = true) &&
                    !candidate.equals("NULL", ignoreCase = true) &&
                    LATIN_LETTER_REGEX.containsMatchIn(candidate)
            }

        val correctionRaw = correctionMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val corrections = if (correctionRaw.isNotBlank()) {
            CorrectionJsonCodec.parseFromModelOutput(
                raw = correctionRaw,
                transcript = cleanTranscription,
                isSpokenStt = true
            )
        } else {
            emptyList()
        }

        return VoiceNoteAnalysis(
            transcription = cleanTranscription,
            englishTranslation = validEnglishTranslation,
            corrections = corrections
        )
    }

    private class VoiceNoteApiException(message: String) : Exception(message)
    private class AudioFileException(message: String) : Exception(message)
}
