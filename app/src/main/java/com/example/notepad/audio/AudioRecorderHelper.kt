package com.example.notepad.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.audio.AudioTranscoder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records 16kHz mono 16-bit PCM speech directly into a valid `.wav` file for Notepad voice notes.
 *
 * Using direct WAV recording eliminates lossy AAC encoding latency and codec mismatches with Gemini STT.
 * Computes live RMS to detect audible speech vs background silence, preventing Gemini hallucinations
 * on empty audio.
 */
class AudioRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderHelper"
        const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SPEECH_RMS_THRESHOLD = 90.0

        const val SILENCE_FRAMES_TRIGGER = 25 // 25 * 20ms = 500ms = 0.5 second silence
        private const val MIN_SPEECH_FRAMES_FOR_PATCH = 8 // ~160ms of speech
        private const val MAX_PATCH_PCM_BYTES = 16000 * 2 * 4 // 4.0 seconds maximum per patch (128 KB)
    }

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0L

    @Volatile
    private var detectedSpeech: Boolean = false

    data class AudioPatch(
        val patchIndex: Int,
        val wavBytes: ByteArray,
        val durationMs: Int,
        val isFinal: Boolean,
        val hasSpeech: Boolean
    )

    @SuppressLint("MissingPermission")
    fun startRecording(onPatchReady: ((AudioPatch) -> Unit)? = null): File? {
        stopRecording()

        return try {
            val audioDir = File(context.filesDir, "audio_notes")
            if (!audioDir.exists()) audioDir.mkdirs()

            val file = File(audioDir, "rec_${System.currentTimeMillis()}.wav")
            currentOutputFile = file
            startTimeMillis = System.currentTimeMillis()
            detectedSpeech = false

            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBufSize * 2).coerceAtLeast(SAMPLE_RATE_HZ)

            val record = createAudioRecord(bufferSize) ?: run {
                Log.e(TAG, "Could not initialize AudioRecord")
                currentOutputFile = null
                return null
            }

            audioRecord = record
            isRecording = true
            record.startRecording()

            recordingThread = thread(start = true, name = "NotepadAudioRecordThread") {
                val pcmBuffer = ShortArray(320) // 20ms chunks
                val byteChunk = ByteArray(640)
                var totalBytesWritten = 0

                val patchPcmBuffer = java.io.ByteArrayOutputStream()
                var currentPatchNumber = 1
                var speechFramesInPatch = 0
                var consecutiveSilenceFrames = 0

                var fos: FileOutputStream? = null
                try {
                    fos = FileOutputStream(file)
                    // Write placeholder 44-byte WAV header; updated on stop
                    fos.write(ByteArray(44))

                    var totalSpeechFrames = 0
                    while (isRecording) {
                        val readCount = record.read(pcmBuffer, 0, pcmBuffer.size)
                        if (readCount > 0) {
                            var sumSq = 0.0
                            var byteIdx = 0
                            for (i in 0 until readCount) {
                                val s = pcmBuffer[i].toInt()
                                sumSq += (s * s).toDouble()
                                byteChunk[byteIdx++] = (s and 0xFF).toByte()
                                byteChunk[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                            }

                            val rms = sqrt(sumSq / readCount)
                            val isSpeechFrame = rms >= SPEECH_RMS_THRESHOLD
                            if (isSpeechFrame) {
                                totalSpeechFrames++
                                speechFramesInPatch++
                                consecutiveSilenceFrames = 0
                                if (totalSpeechFrames >= 3) {
                                    detectedSpeech = true
                                }
                            } else {
                                consecutiveSilenceFrames++
                            }

                            // Write to full recording for Notepad audio playback preview
                            fos.write(byteChunk, 0, byteIdx)
                            totalBytesWritten += byteIdx

                            // Accumulate current patch
                            patchPcmBuffer.write(byteChunk, 0, byteIdx)

                            // Check silence or max duration trigger for patch slicing
                            if (onPatchReady != null) {
                                val hasEnoughSpeech = speechFramesInPatch >= MIN_SPEECH_FRAMES_FOR_PATCH
                                val silenceTrigger = hasEnoughSpeech && consecutiveSilenceFrames >= SILENCE_FRAMES_TRIGGER
                                val maxDurationTrigger = hasEnoughSpeech && patchPcmBuffer.size() >= MAX_PATCH_PCM_BYTES

                                if (silenceTrigger || maxDurationTrigger) {
                                    val pcm = patchPcmBuffer.toByteArray()
                                    patchPcmBuffer.reset()
                                    speechFramesInPatch = 0
                                    consecutiveSilenceFrames = 0

                                    val pcmDurationMs = ((pcm.size.toDouble() / (SAMPLE_RATE_HZ * 2)) * 1000).toInt()
                                    val patchWav = AudioTranscoder.pcmToWav(pcm, SAMPLE_RATE_HZ, 1, 16)
                                    val patch = AudioPatch(
                                        patchIndex = currentPatchNumber++,
                                        wavBytes = patchWav,
                                        durationMs = pcmDurationMs,
                                        isFinal = false,
                                        hasSpeech = true
                                    )
                                    try {
                                        onPatchReady(patch)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error emitting audio patch #$currentPatchNumber", e)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error streaming PCM to WAV file", e)
                } finally {
                    // Final patch emission upon recording completion
                    if (onPatchReady != null) {
                        val remainingPcm = patchPcmBuffer.toByteArray()
                        patchPcmBuffer.reset()
                        val hasSpeech = speechFramesInPatch >= 4
                        val pcmDurationMs = ((remainingPcm.size.toDouble() / (SAMPLE_RATE_HZ * 2)) * 1000).toInt()
                        val remainingWav = if (remainingPcm.isNotEmpty()) {
                            AudioTranscoder.pcmToWav(remainingPcm, SAMPLE_RATE_HZ, 1, 16)
                        } else {
                            ByteArray(0)
                        }
                        val finalPatch = AudioPatch(
                            patchIndex = currentPatchNumber++,
                            wavBytes = remainingWav,
                            durationMs = pcmDurationMs,
                            isFinal = true,
                            hasSpeech = hasSpeech
                        )
                        try {
                            onPatchReady(finalPatch)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting final audio patch", e)
                        }
                    }

                    runCatching { fos?.flush() }
                    runCatching { fos?.close() }

                    // Finalize WAV header with exact PCM byte count
                    finalizeWavHeader(file, totalBytesWritten)
                }
            }

            Log.d(TAG, "Started recording WAV to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cancelRecording()
            null
        }
    }

    fun stopRecording(): RecordingResult? {
        if (!isRecording) return null
        isRecording = false

        val record = audioRecord
        val threadToJoin = recordingThread
        audioRecord = null
        recordingThread = null

        try {
            record?.let {
                runCatching { it.stop() }
                runCatching { threadToJoin?.join(2000L) }
                runCatching { it.release() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        val file = currentOutputFile ?: return null
        currentOutputFile = null

        val durationSec = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt().coerceAtLeast(1)
        return RecordingResult(
            file = file,
            durationSec = durationSec,
            hasSpeech = true
        )
    }

    fun cancelRecording() {
        isRecording = false
        val record = audioRecord
        audioRecord = null
        val threadToJoin = recordingThread
        recordingThread = null

        try {
            record?.let {
                runCatching { it.stop() }
                runCatching { threadToJoin?.join(150L) }
                runCatching { it.release() }
            }
        } catch (_: Exception) {}

        currentOutputFile?.delete()
        currentOutputFile = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(source, SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            runCatching { candidate.release() }
        }
        return null
    }

    private fun finalizeWavHeader(file: File, pcmBytesWritten: Int) {
        if (!file.exists() || pcmBytesWritten <= 0) return
        try {
            val header = AudioTranscoder.pcmToWav(
                pcm = ByteArray(0),
                sampleRate = SAMPLE_RATE_HZ,
                channels = 1,
                bitsPerSample = 16
            )
            // Fix sizes in the 44-byte header
            val totalDataLen = pcmBytesWritten
            val totalFileSizeMinus8 = 36 + totalDataLen

            header[4] = (totalFileSizeMinus8 and 0xFF).toByte()
            header[5] = ((totalFileSizeMinus8 shr 8) and 0xFF).toByte()
            header[6] = ((totalFileSizeMinus8 shr 16) and 0xFF).toByte()
            header[7] = ((totalFileSizeMinus8 shr 24) and 0xFF).toByte()

            header[40] = (totalDataLen and 0xFF).toByte()
            header[41] = ((totalDataLen shr 8) and 0xFF).toByte()
            header[42] = ((totalDataLen shr 16) and 0xFF).toByte()
            header[43] = ((totalDataLen shr 24) and 0xFF).toByte()

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header, 0, 44)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize WAV header", e)
        }
    }

    data class RecordingResult(
        val file: File,
        val durationSec: Int,
        val hasSpeech: Boolean = true
    )
}
