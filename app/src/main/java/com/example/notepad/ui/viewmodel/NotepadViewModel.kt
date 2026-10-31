package com.example.notepad.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.network.AvalAiClient
import com.example.network.CorrectionJsonCodec
import com.example.notepad.audio.AudioPlayerHelper
import com.example.notepad.audio.AudioRecorderHelper
import com.example.notepad.data.gemini.GeminiPreferences
import com.example.notepad.data.gemini.GeminiService
import com.example.notepad.data.model.MediaItem
import com.example.notepad.data.model.MediaType
import com.example.notepad.data.model.NoteLine
import com.example.notepad.data.model.NotepadBlock
import com.example.notepad.data.model.NotepadTab
import com.example.notepad.data.model.hasContent
import com.example.notepad.data.model.removeEmptySecondaryBlock
import com.example.notepad.data.model.removeLine
import com.example.notepad.data.repository.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.UUID

class NotepadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NotesRepository.getInstance(application)
    private val geminiService = GeminiService()
    val geminiPreferences = GeminiPreferences(application)
    val recorderHelper = AudioRecorderHelper(application)
    val playerHelper = AudioPlayerHelper(application)

    val tabs: StateFlow<List<NotepadTab>> = repository.tabs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var activeRecordingBlockId: String? = null
    private val emptyBlockCleanupJobs = mutableMapOf<String, Job>()
    private val transcriptionRetryJobs = mutableMapOf<String, Job>()
    private val focusedLineIdsByBlock = mutableMapOf<String, MutableSet<String>>()

    // For image preview modal (smooth expand)
    private val _previewImageItem = MutableStateFlow<MediaItem?>(null)
    val previewImageItem: StateFlow<MediaItem?> = _previewImageItem.asStateFlow()

    // For audio review dialog (matching Screenshot 1)
    private val _selectedAudioItem = MutableStateFlow<MediaItem?>(null)
    val selectedAudioItem: StateFlow<MediaItem?> = _selectedAudioItem.asStateFlow()

    // For item deletion popup (blockId to MediaItem)
    private val _itemToDelete = MutableStateFlow<Pair<String, MediaItem>?>(null)
    val itemToDelete: StateFlow<Pair<String, MediaItem>?> = _itemToDelete.asStateFlow()

    // Dialog state for new general notepad tab
    private val _showNewTabDialog = MutableStateFlow(false)
    val showNewTabDialog: StateFlow<Boolean> = _showNewTabDialog.asStateFlow()

    // Dialog state for tab long-press action (Rename or Delete)
    private val _tabActionTarget = MutableStateFlow<Pair<Int, NotepadTab>?>(null)
    val tabActionTarget: StateFlow<Pair<Int, NotepadTab>?> = _tabActionTarget.asStateFlow()

    val currentlyPlayingId = playerHelper.currentlyPlayingId
    val currentPositionMs = playerHelper.currentPositionMs
    val durationMs = playerHelper.durationMs

    val avalaiApiKey = geminiPreferences.avalaiApiKey

    init {
        viewModelScope.launch {
            repository.loadData()
            repository.tabs.value.forEach { tab ->
                tab.blocks.drop(1)
                    .filterNot(NotepadBlock::hasContent)
                    .forEach { block -> scheduleEmptyBlockRemoval(tab.id, block.id) }
            }
        }
        viewModelScope.launch {
            repository.tabs.collect { updatedTabs ->
                if (updatedTabs.isNotEmpty() && _selectedTabIndex.value !in updatedTabs.indices) {
                    _selectedTabIndex.value = updatedTabs.lastIndex.coerceAtLeast(0)
                }
                val selectedAudioId = _selectedAudioItem.value?.id
                if (selectedAudioId != null) {
                    val refreshed = updatedTabs
                        .asSequence()
                        .flatMap { it.blocks.asSequence() }
                        .flatMap { it.mediaItems.asSequence() }
                        .firstOrNull { it.id == selectedAudioId }
                    if (refreshed != null) {
                        _selectedAudioItem.value = refreshed
                    }
                }
            }
        }
    }

    fun selectTab(index: Int) {
        val currentTabs = tabs.value
        if (index in currentTabs.indices) {
            _selectedTabIndex.value = index
        }
    }

    fun openTabActionMenu(index: Int, tab: NotepadTab) {
        _tabActionTarget.value = Pair(index, tab)
    }

    fun closeTabActionMenu() {
        _tabActionTarget.value = null
    }

    fun renameTab(index: Int, newTitle: String) {
        if (index !in tabs.value.indices) return
        val cleanTitle = newTitle.trim()
        viewModelScope.launch {
            repository.updateTabs { currentTabs ->
                if (index !in currentTabs.indices) {
                    currentTabs
                } else {
                    currentTabs.toMutableList().apply {
                        val title = cleanTitle.ifEmpty { this[index].title }
                        set(index, this[index].copy(title = title))
                    }
                }
            }
            closeTabActionMenu()
        }
    }

    fun deleteTab(index: Int) {
        val currentTabs = tabs.value
        if (currentTabs.size <= 1 || index !in currentTabs.indices) return
        viewModelScope.launch {
            repository.updateTabs { latestTabs ->
                if (latestTabs.size <= 1 || index !in latestTabs.indices) {
                    latestTabs
                } else {
                    latestTabs.toMutableList().apply { removeAt(index) }
                }
            }
            _selectedTabIndex.value = (index - 1).coerceAtLeast(0)
            closeTabActionMenu()
        }
    }

    fun openNewTabDialog() {
        _showNewTabDialog.value = true
    }

    fun closeNewTabDialog() {
        _showNewTabDialog.value = false
    }

    fun refreshAiSettings() {
        geminiPreferences.refreshFromSharedSettings()
    }

    fun updateAiSettings(avalaiKey: String) {
        geminiPreferences.updateSettings(avalaiKey)
    }

    fun resetToDefaultData() {
        viewModelScope.launch {
            repository.resetToDefault()
            _selectedTabIndex.value = 0
        }
    }

    fun createNewTab(title: String) {
        val cleanTitle = title.trim().ifEmpty { "notepad ${tabs.value.size + 1}" }
        val newTab = NotepadTab(
            title = cleanTitle.lowercase(),
            blocks = listOf(
                NotepadBlock(
                    title = "",
                    lines = List(INITIAL_LINE_COUNT) { NoteLine(text = "") },
                    mediaItems = emptyList()
                )
            )
        )
        viewModelScope.launch {
            repository.updateTabs { currentTabs -> currentTabs + newTab }
            _selectedTabIndex.value = repository.tabs.value.lastIndex
            _showNewTabDialog.value = false
        }
    }

    fun addSubNotepad() {
        val currentTab = tabs.value.getOrNull(_selectedTabIndex.value) ?: return
        val newBlock = NotepadBlock(
            title = "",
            lines = List(INITIAL_LINE_COUNT) { NoteLine(text = "") },
            mediaItems = emptyList()
        )

        viewModelScope.launch {
            repository.updateTabs { currentTabs ->
                currentTabs.map { tab ->
                    if (tab.id == currentTab.id) tab.copy(blocks = tab.blocks + newBlock) else tab
                }
            }
            scheduleEmptyBlockRemoval(currentTab.id, newBlock.id)
        }
    }

    fun keepBlock(blockId: String) {
        emptyBlockCleanupJobs.remove(blockId)?.cancel()
    }

    fun setLineTyping(blockId: String, lineId: String, isTyping: Boolean) {
        if (isTyping) {
            focusedLineIdsByBlock.getOrPut(blockId) { mutableSetOf() }.add(lineId)
            keepBlock(blockId)
        } else {
            val focusedLineIds = focusedLineIdsByBlock[blockId] ?: return
            focusedLineIds.remove(lineId)
            if (focusedLineIds.isEmpty()) {
                focusedLineIdsByBlock.remove(blockId)
                resumeEmptyBlockCleanup(blockId)
            }
        }
    }

    fun resumeEmptyBlockCleanup(blockId: String) {
        val tab = repository.tabs.value.firstOrNull { candidate ->
            candidate.blocks.any { it.id == blockId }
        } ?: return
        scheduleEmptyBlockRemoval(tab.id, blockId)
    }

    fun removeLine(blockId: String, lineId: String) {
        updateBlock(blockId) { block ->
            block.removeLine(lineId)
        }
    }

    fun insertLineAfter(
        blockId: String,
        currentLineId: String,
        text: String = "",
        newLineId: String = UUID.randomUUID().toString()
    ): String {
        updateBlock(blockId) { block ->
            val index = block.lines.indexOfFirst { it.id == currentLineId }
            val newLines = block.lines.toMutableList()
            val newLine = NoteLine(id = newLineId, text = text)
            if (index >= 0) {
                newLines.add(index + 1, newLine)
            } else {
                newLines.add(newLine)
            }
            block.copy(lines = newLines)
        }
        return newLineId
    }

    fun splitLineOnEnter(
        blockId: String,
        currentLineId: String,
        beforeCursor: String,
        afterCursor: String,
        newLineId: String = UUID.randomUUID().toString()
    ): String {
        updateBlock(blockId) { block ->
            val index = block.lines.indexOfFirst { it.id == currentLineId }
            if (index < 0) return@updateBlock block
            val updatedCurrent = block.lines[index].copy(text = beforeCursor)
            val newLine = NoteLine(id = newLineId, text = afterCursor)
            val newLines = block.lines.toMutableList().apply {
                set(index, updatedCurrent)
                add(index + 1, newLine)
            }
            block.copy(lines = newLines)
        }
        return newLineId
    }

    fun updateLine(blockId: String, lineId: String, newText: String) {
        updateBlock(blockId) { block ->
            val updatedLines = block.lines.map {
                if (it.id == lineId) it.copy(text = newText) else it
            }
            block.copy(lines = updatedLines)
        }
    }

    private var activeRecordingItemId: String? = null
    private var activePatchChannel: Channel<AudioRecorderHelper.AudioPatch>? = null
    private var patchProcessingJob: Job? = null
    @Volatile private var liveAccumulatedTranscription = ""
    @Volatile private var liveAccumulatedTranslation = ""
    private val liveAccumulatedCorrections = mutableListOf<com.example.network.LinguisticCorrection>()

    // Voice recording management
    fun startVoiceRecording(blockId: String) {
        keepBlock(blockId)
        activeRecordingBlockId = blockId

        val tempItemId = UUID.randomUUID().toString()
        activeRecordingItemId = tempItemId
        liveAccumulatedTranscription = ""
        liveAccumulatedTranslation = ""
        synchronized(liveAccumulatedCorrections) { liveAccumulatedCorrections.clear() }

        val patchChannel = Channel<AudioRecorderHelper.AudioPatch>(Channel.UNLIMITED)
        activePatchChannel = patchChannel

        val file = recorderHelper.startRecording { patch ->
            patchChannel.trySend(patch)
        }

        if (file != null) {
            AvalAiClient.warmUpConnectionAsync()
            _isRecording.value = true
            _recordingDurationSec.value = 0
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                while (_isRecording.value) {
                    delay(1000)
                    _recordingDurationSec.value += 1
                }
            }

            patchProcessingJob?.cancel()
            patchProcessingJob = viewModelScope.launch {
                var accumulatedTaggedTranscription = ""
                var accumulatedCleanTranscription = ""
                var accumulatedTranslation = ""
                val accumulatedCorrections = mutableListOf<com.example.network.LinguisticCorrection>()
                var nextExpectedPatch = 1
                val pendingPatches = TreeMap<Int, AudioRecorderHelper.AudioPatch>()
                var finalPatchReceived = false

                for (patch in patchChannel) {
                    pendingPatches[patch.patchIndex] = patch
                    if (patch.isFinal) {
                        finalPatchReceived = true
                    }

                    // Process patches in strict numerical sequence: 1 -> 2 -> 3...
                    // Guarantees patch 2 is never shown or committed before patch 1 has arrived.
                    while (pendingPatches.containsKey(nextExpectedPatch)) {
                        val currentPatch = pendingPatches.remove(nextExpectedPatch)!!

                        if (currentPatch.hasSpeech && currentPatch.wavBytes.isNotEmpty()) {
                            // Extract recent tagged context:
                            // Formats e.g. "{patch 1} i would like to {patch 2} speak to my friend"
                            val recentContext = if (accumulatedTaggedTranscription.isNotBlank()) {
                                val tokens = accumulatedTaggedTranscription.trim().split("\\s+".toRegex())
                                if (tokens.size > 30) {
                                    "..." + tokens.takeLast(30).joinToString(" ")
                                } else {
                                    accumulatedTaggedTranscription.trim()
                                }
                            } else {
                                ""
                            }

                            geminiPreferences.refreshFromSharedSettings()
                            val result = geminiService.analyzeVoiceNotePatch(
                                audioBytes = currentPatch.wavBytes,
                                recentContext = recentContext,
                                patchNumber = currentPatch.patchIndex,
                                avalaiKeyCandidates = geminiPreferences.getOrderedKeyCandidates(),
                                onAvalaiKeySucceeded = { poolIdx ->
                                    geminiPreferences.markKeySucceeded(poolIdx)
                                }
                            )

                            if (result.isSuccess) {
                                val analysis = result.getOrThrow()
                                // Ensure all patch markers are stripped for clean user display
                                val cleanPatchText = GeminiService.stripPatchMarkers(analysis.transcription.trim())
                                if (cleanPatchText.isNotBlank() &&
                                    !cleanPatchText.equals("NO_SPEECH", ignoreCase = true) &&
                                    !cleanPatchText.equals("SILENCE", ignoreCase = true) &&
                                    !cleanPatchText.equals("[SILENCE]", ignoreCase = true) &&
                                    !cleanPatchText.equals(GeminiService.SILENCE_TRANSCRIPTION, ignoreCase = true)
                                ) {
                                    // Internal representation keeping patch tags for AI context tracking:
                                    val taggedToken = "{patch ${currentPatch.patchIndex}} $cleanPatchText"
                                    accumulatedTaggedTranscription = if (accumulatedTaggedTranscription.isBlank()) {
                                        taggedToken
                                    } else {
                                        "$accumulatedTaggedTranscription $taggedToken"
                                    }

                                    // User-facing display: strictly NO "{patch X}" tokens visible!
                                    accumulatedCleanTranscription = if (accumulatedCleanTranscription.isBlank()) {
                                        cleanPatchText
                                    } else {
                                        "$accumulatedCleanTranscription $cleanPatchText"
                                    }
                                }

                                val rawTrans = analysis.englishTranslation?.trim()
                                val cleanTrans = if (!rawTrans.isNullOrBlank() && !rawTrans.equals("NONE", ignoreCase = true)) {
                                    GeminiService.stripPatchMarkers(rawTrans)
                                } else {
                                    ""
                                }
                                if (cleanTrans.isNotBlank()) {
                                    accumulatedTranslation = if (accumulatedTranslation.isBlank()) {
                                        cleanTrans
                                    } else {
                                        "$accumulatedTranslation $cleanTrans"
                                    }
                                }

                                accumulatedCorrections.addAll(analysis.corrections)

                                liveAccumulatedTranscription = accumulatedCleanTranscription
                                liveAccumulatedTranslation = accumulatedTranslation
                                synchronized(liveAccumulatedCorrections) {
                                    liveAccumulatedCorrections.clear()
                                    liveAccumulatedCorrections.addAll(accumulatedCorrections)
                                }

                                // Incrementally update the Notepad block UI in real time
                                // with clean, seamless transcription (never showing {patch X} to the user)
                                updateBlock(blockId) { block ->
                                    block.copy(mediaItems = block.mediaItems.map { media ->
                                        if (media.id == tempItemId) {
                                            media.copy(
                                                transcription = accumulatedCleanTranscription.ifBlank { null },
                                                englishTranslation = accumulatedTranslation.ifBlank { null },
                                                corrections = accumulatedCorrections.toList()
                                            )
                                        } else {
                                            media
                                        }
                                    })
                                }
                            }
                        }

                        val wasFinal = currentPatch.isFinal
                        nextExpectedPatch++
                        if (wasFinal) {
                            break
                        }
                    }

                    if (finalPatchReceived && pendingPatches.isEmpty()) {
                        break
                    }
                }
            }
        } else {
            activeRecordingItemId = null
            activeRecordingBlockId = null
            activePatchChannel?.close()
            activePatchChannel = null
            updateBlock(blockId) { block ->
                block.copy(mediaItems = block.mediaItems.filterNot { it.id == tempItemId })
            }
            resumeEmptyBlockCleanup(blockId)
        }
    }

    fun stopVoiceRecordingAndTranscribe(blockId: String) {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingTimerJob = null

        val itemId = activeRecordingItemId
        val targetBlockId = activeRecordingBlockId ?: blockId
        activeRecordingItemId = null
        activeRecordingBlockId = null

        // Immediately stop recording hardware and close patch channel
        val result = recorderHelper.stopRecording()
        activePatchChannel?.close()
        activePatchChannel = null

        if (result == null || itemId == null) {
            resumeEmptyBlockCleanup(targetBlockId)
            return
        }

        // Commit the voice note squircle now that the finger is released
        val voiceMediaItem = MediaItem(
            id = itemId,
            type = MediaType.AUDIO,
            filePath = result.file.absolutePath,
            isProcessing = true,
            isTranscribed = false,
            durationSec = result.durationSec,
            transcription = liveAccumulatedTranscription.ifBlank { null },
            englishTranslation = liveAccumulatedTranslation.ifBlank { null },
            corrections = synchronized(liveAccumulatedCorrections) { liveAccumulatedCorrections.toList() }
        )
        updateBlock(targetBlockId) { block ->
            block.copy(mediaItems = block.mediaItems + voiceMediaItem)
        }

        viewModelScope.launch {
            try {
                // Wait for all sequential patches to complete their network turns (with timeout protection)
                withTimeoutOrNull(20000L) {
                    patchProcessingJob?.join()
                }
            } catch (_: Exception) {}

            updateBlock(targetBlockId) { block ->
                block.copy(mediaItems = block.mediaItems.map { media ->
                    if (media.id == itemId) {
                        val finalTrans = liveAccumulatedTranscription.ifBlank { media.transcription }
                        val finalTr = liveAccumulatedTranslation.ifBlank { media.englishTranslation }
                        val finalCor = synchronized(liveAccumulatedCorrections) {
                            if (liveAccumulatedCorrections.isNotEmpty()) liveAccumulatedCorrections.toList() else media.corrections
                        }
                        media.copy(
                            transcription = finalTrans,
                            englishTranslation = finalTr,
                            corrections = finalCor,
                            isProcessing = false,
                            isTranscribed = !finalTrans.isNullOrBlank()
                        )
                    } else {
                        media
                    }
                })
            }
            resumeEmptyBlockCleanup(targetBlockId)
        }
    }

    fun retryAudioTranscription(blockId: String, itemId: String) {
        val currentBlock = tabs.value
            .flatMap { it.blocks }
            .firstOrNull { it.id == blockId }
            ?: return

        val item = currentBlock.mediaItems.firstOrNull { it.id == itemId } ?: return
        if (transcriptionRetryJobs[itemId]?.isActive == true) return

        val resettingItem = item.copy(
            isProcessing = true,
            isTranscribed = false,
            transcription = null,
            englishTranslation = null,
            corrections = emptyList()
        )
        updateBlock(blockId) { block ->
            block.copy(mediaItems = block.mediaItems.map { media ->
                if (media.id == itemId) resettingItem else media
            })
        }
        startTranscriptionWithRetry(blockId, resettingItem)
    }

    fun applyAudioCorrection(blockId: String, itemId: String, correctionId: String) {
        updateBlock(blockId) { block ->
            block.copy(mediaItems = block.mediaItems.map { media ->
                if (media.id != itemId) return@map media
                val currentText = media.transcription ?: return@map media
                val (updatedText, remainingCorrections) = CorrectionJsonCodec.applyCorrectionToText(
                    text = currentText,
                    corrections = media.corrections,
                    correctionId = correctionId
                )
                media.copy(
                    transcription = updatedText,
                    corrections = remainingCorrections
                )
            })
        }
    }

    /**
     * Transcribes and analyzes a voice memo for red/yellow corrections and optional
     * Persian-to-English translation, retrying transient failures automatically.
     */
    private fun startTranscriptionWithRetry(
        blockId: String,
        item: MediaItem,
        retriesRemaining: Int = TRANSCRIPTION_MAX_ATTEMPTS
    ) {
        transcriptionRetryJobs.remove(item.id)?.cancel()
        transcriptionRetryJobs[item.id] = viewModelScope.launch {
            try {
                var attemptsLeft = retriesRemaining.coerceAtLeast(1)
                val audioFile = File(item.filePath)

                while (isActive && attemptsLeft > 0) {
                    attemptsLeft -= 1
                    geminiPreferences.refreshFromSharedSettings()
                    val result = geminiService.analyzeVoiceNote(
                        audioFile = audioFile,
                        avalaiKeyCandidates = geminiPreferences.getOrderedKeyCandidates(),
                        onAvalaiKeySucceeded = { poolIdx ->
                            geminiPreferences.markKeySucceeded(poolIdx)
                        }
                    )

                    if (result.isSuccess) {
                        val analysis = result.getOrThrow()
                        updateBlock(blockId) { block ->
                            block.copy(mediaItems = block.mediaItems.map { media ->
                                if (media.id == item.id) {
                                    media.copy(
                                        isProcessing = false,
                                        isTranscribed = true,
                                        transcription = analysis.transcription,
                                        englishTranslation = analysis.englishTranslation,
                                        corrections = analysis.corrections
                                    )
                                } else {
                                    media
                                }
                            })
                        }
                        return@launch
                    }

                    if (attemptsLeft > 0) {
                        delay(TRANSCRIPTION_RETRY_DELAY_MS)
                    }
                }

                updateBlock(blockId) { block ->
                    block.copy(mediaItems = block.mediaItems.map { media ->
                        if (media.id == item.id) {
                            media.copy(
                                isProcessing = false,
                                isTranscribed = false,
                                transcription = null,
                                englishTranslation = null,
                                corrections = emptyList()
                            )
                        } else {
                            media
                        }
                    })
                }
            } finally {
                transcriptionRetryJobs.remove(item.id)
            }
        }
    }

    fun cancelVoiceRecording() {
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        patchProcessingJob?.cancel()
        patchProcessingJob = null
        activePatchChannel?.close()
        activePatchChannel = null
        liveAccumulatedTranscription = ""
        liveAccumulatedTranslation = ""
        synchronized(liveAccumulatedCorrections) { liveAccumulatedCorrections.clear() }

        val itemId = activeRecordingItemId
        val blockId = activeRecordingBlockId
        activeRecordingItemId = null
        activeRecordingBlockId = null

        recorderHelper.cancelRecording()
        if (itemId != null && blockId != null) {
            updateBlock(blockId) { block ->
                block.copy(mediaItems = block.mediaItems.filterNot { it.id == itemId })
            }
        }
        blockId?.let(::resumeEmptyBlockCleanup)
    }

    // Image capture management
    fun addImageBitmap(blockId: String, bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val imagesDir = File(getApplication<Application>().filesDir, "image_notes")
                if (!imagesDir.exists()) imagesDir.mkdirs()

                val uniqueTag = UUID.randomUUID().toString().take(8)
                val file = File(imagesDir, "img_${System.currentTimeMillis()}_$uniqueTag.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                val item = MediaItem(
                    type = MediaType.IMAGE,
                    filePath = file.absolutePath,
                    isTranscribed = false
                )

                updateBlock(blockId) { block ->
                    block.copy(mediaItems = block.mediaItems + item)
                }
            } catch (e: Exception) {
                Log.e("NotepadViewModel", "Failed to save image", e)
                resumeEmptyBlockCleanup(blockId)
            }
        }
    }

    fun addImageUri(blockId: String, uri: Uri) {
        addImageUris(blockId, listOf(uri))
    }

    fun addImageUris(blockId: String, uris: List<Uri>) {
        if (uris.isEmpty()) {
            resumeEmptyBlockCleanup(blockId)
            return
        }
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val addedItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val imagesDir = File(context.filesDir, "image_notes")
                    if (!imagesDir.exists()) imagesDir.mkdirs()

                    val baseNow = System.currentTimeMillis()
                    buildList {
                        for ((index, uri) in uris.withIndex()) {
                            val inputStream = if (uri.scheme == "file" && !uri.path.isNullOrEmpty()) {
                                runCatching { File(uri.path!!).inputStream() }.getOrNull()
                            } else {
                                context.contentResolver.openInputStream(uri)
                            } ?: continue
                            val uniqueTag = UUID.randomUUID().toString().take(8)
                            val file = File(imagesDir, "img_${baseNow}_${index}_$uniqueTag.jpg")
                            inputStream.use { stream ->
                                FileOutputStream(file).use { out ->
                                    stream.copyTo(out)
                                }
                            }
                            add(
                                MediaItem(
                                    type = MediaType.IMAGE,
                                    filePath = file.absolutePath,
                                    isTranscribed = false,
                                    timestamp = baseNow + index
                                )
                            )
                        }
                    }
                }

                if (addedItems.isEmpty()) {
                    resumeEmptyBlockCleanup(blockId)
                    return@launch
                }

                updateBlock(blockId) { block ->
                    block.copy(mediaItems = block.mediaItems + addedItems)
                }
            } catch (e: Exception) {
                Log.e("NotepadViewModel", "Failed to copy image URIs", e)
                resumeEmptyBlockCleanup(blockId)
            }
        }
    }

    // Delete popup
    fun requestDeleteItem(blockId: String, item: MediaItem) {
        _itemToDelete.value = Pair(blockId, item)
    }

    fun confirmDeleteItem() {
        val target = _itemToDelete.value ?: return
        val blockId = target.first
        val item = target.second

        transcriptionRetryJobs.remove(item.id)?.cancel()

        updateBlock(blockId) { block ->
            block.copy(mediaItems = block.mediaItems.filterNot { it.id == item.id })
        }
        _itemToDelete.value = null
    }

    fun dismissDeletePopup() {
        _itemToDelete.value = null
    }

    // Previews & Details
    fun showImagePreview(item: MediaItem) {
        _previewImageItem.value = item
    }

    fun dismissImagePreview() {
        _previewImageItem.value = null
    }

    fun showAudioDetails(item: MediaItem) {
        _selectedAudioItem.value = item
        if (item.filePath.isNotEmpty()) {
            playerHelper.playAudio(item.id, item.filePath)
        }
    }

    fun dismissAudioDetails() {
        _selectedAudioItem.value = null
        playerHelper.stopAudio()
    }

    fun toggleActiveAudioPlayback(item: MediaItem) {
        if (playerHelper.isPlaying(item.id)) {
            playerHelper.stopAudio()
        } else {
            if (item.filePath.isNotEmpty()) {
                playerHelper.playAudio(item.id, item.filePath)
            }
        }
    }

    private fun updateBlock(blockId: String, transform: (NotepadBlock) -> NotepadBlock) {
        keepBlock(blockId)
        viewModelScope.launch {
            repository.updateTabs { currentTabs ->
                currentTabs.map { tab ->
                    if (tab.blocks.none { it.id == blockId }) {
                        tab
                    } else {
                        tab.copy(blocks = tab.blocks.map { block ->
                            if (block.id == blockId) transform(block) else block
                        })
                    }
                }
            }
            resumeEmptyBlockCleanup(blockId)
        }
    }

    private fun scheduleEmptyBlockRemoval(tabId: String, blockId: String) {
        emptyBlockCleanupJobs.remove(blockId)?.cancel()

        val tab = repository.tabs.value.firstOrNull { it.id == tabId } ?: return
        val blockIndex = tab.blocks.indexOfFirst { it.id == blockId }
        if (blockIndex <= 0 || tab.blocks.size <= 1 || tab.blocks[blockIndex].hasContent()) return

        emptyBlockCleanupJobs[blockId] = viewModelScope.launch {
            delay(EMPTY_BLOCK_REMOVAL_DELAY_MS)
            repository.updateTabs { currentTabs ->
                currentTabs.map { currentTab ->
                    if (currentTab.id == tabId) {
                        currentTab.removeEmptySecondaryBlock(blockId)
                    } else {
                        currentTab
                    }
                }
            }
            emptyBlockCleanupJobs.remove(blockId)
        }
    }

    private companion object {
        const val INITIAL_LINE_COUNT = 3
        const val EMPTY_BLOCK_REMOVAL_DELAY_MS = 10_000L
        const val TRANSCRIPTION_RETRY_DELAY_MS = 5_000L
        const val TRANSCRIPTION_MAX_ATTEMPTS = 3
    }

    override fun onCleared() {
        super.onCleared()
        emptyBlockCleanupJobs.values.forEach(Job::cancel)
        emptyBlockCleanupJobs.clear()
        transcriptionRetryJobs.values.forEach(Job::cancel)
        transcriptionRetryJobs.clear()
        playerHelper.stopAudio()
        recorderHelper.cancelRecording()
    }
}
