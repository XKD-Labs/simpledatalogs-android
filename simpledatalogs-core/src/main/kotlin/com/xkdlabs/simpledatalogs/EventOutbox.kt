package com.xkdlabs.simpledatalogs

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/** One sealed batch file: an NDJSON request body waiting for its 202. */
internal data class SealedBatch(
    /** Where the batch lives on disk. */
    val file: File,

    /** How many events (lines) the batch holds. */
    val eventCount: Int,

    /** The size of the batch body in bytes. */
    val byteCount: Int,
)

/** The size limits the outbox enforces. */
internal data class OutboxLimits(
    /** The most events one sealed batch may hold. */
    val maxBatchEventCount: Int,

    /** The most bytes one sealed batch (one request body) may hold. */
    val maxBatchBytes: Int,

    /** The most events kept across the journal and every sealed batch. */
    val maxTotalEventCount: Int,

    /** The most bytes kept across the journal and every sealed batch. */
    val maxTotalBytes: Long,

    /** The most sealed batch files kept, so days offline never leave thousands of files. */
    val maxSealedBatchCount: Int,
)

/** What happened to a batch the server called too large. */
internal enum class BatchSplitOutcome {
    /** The batch was replaced by its two halves. */
    SPLIT,

    /** The batch held a single event, which was dropped because it can never be accepted. */
    DROPPED_LONE_EVENT,

    /** The batch had already left the outbox, e.g. trimmed by the cap while in flight. */
    ALREADY_REMOVED,
}

/** The crash-safe disk outbox: an append-only journal, plus sealed batch files deleted only after a 202; worker thread only. */
internal class EventOutbox(
    /** The outbox root folder. */
    private val directory: File,

    /** Batch and total size limits. */
    private val limits: OutboxLimits,

    /** Stamps batch file names. */
    private val clock: Clock,

    /** Where problems are logged. */
    private val logger: SdkLogger,
) {
    /** File names and extensions inside the outbox folder. */
    companion object {
        /** The live journal's file name. */
        const val JOURNAL_FILE_NAME = "journal.ndjson"

        /** Every journal file name, live or set aside after a failed recovery, starts with this. */
        const val JOURNAL_FILE_PREFIX = "journal"

        /** The folder of sealed batches. */
        const val BATCHES_FOLDER_NAME = "batches"

        /** Every sealed batch file name starts with this. */
        const val BATCH_FILE_PREFIX = "batch-"

        /** Journals and sealed batches both end with this. */
        const val BATCH_FILE_SUFFIX = ".ndjson"

        /** The extra extension of a batch set aside after failing to read. */
        const val UNREADABLE_EXTENSION = "unreadable"

        /** The extra extension of a batch ingest refused as unsupported (415); it is never resent. */
        const val UNSUPPORTED_EXTENSION = "unsupported"

        /** The suffix of a half-written file, removed by recovery. */
        private const val TEMPORARY_SUFFIX = ".tmp"

        /** The NDJSON line separator. */
        private const val NEWLINE_BYTE: Byte = '\n'.code.toByte()

        /** The bytes one event line takes on disk and on the wire, including its newline. */
        fun storedByteCount(eventLine: ByteArray): Int {
            return eventLine.size + 1
        }

        /** Splits an NDJSON body into its non-empty lines. */
        fun lines(ndjsonBody: ByteArray): List<ByteArray> {
            val eventLines = ArrayList<ByteArray>()
            var lineStart = 0

            for (byteIndex in ndjsonBody.indices) {
                if (ndjsonBody[byteIndex] == NEWLINE_BYTE) {
                    if (byteIndex > lineStart) {
                        eventLines.add(ndjsonBody.copyOfRange(lineStart, byteIndex))
                    }

                    lineStart = byteIndex + 1
                }
            }

            // A last line without a newline still counts here.
            if (lineStart < ndjsonBody.size) {
                eventLines.add(ndjsonBody.copyOfRange(lineStart, ndjsonBody.size))
            }

            return eventLines
        }
    }

    /** The live journal every tracked event is appended to. */
    val journalFile: File = File(directory, JOURNAL_FILE_NAME)

    /** The folder holding sealed batches. */
    val batchesDirectory: File = File(directory, BATCHES_FOLDER_NAME)

    /** How many events the journal holds right now. */
    var journalEventCount: Int = 0
        private set

    /** How many bytes the journal holds right now. */
    var journalByteCount: Int = 0
        private set

    /** Every sealed batch on disk, oldest first. */
    private val sealedBatches = ArrayList<SealedBatch>()

    /** A per-launch counter that keeps file names sorted within one millisecond. */
    private var fileNameSequence = 0

    /** Events held across the journal and every sealed batch. */
    val totalEventCount: Int
        get() = journalEventCount + sealedBatches.sumOf { batch -> batch.eventCount }

    /** Bytes held across the journal and every sealed batch. */
    val totalByteCount: Long
        get() = journalByteCount + sealedBatches.sumOf { batch -> batch.byteCount.toLong() }

    /** A copy of the sealed batch index, oldest first. */
    val sealedBatchSnapshot: List<SealedBatch>
        get() = sealedBatches.toList()

    /** The batch the send loop should try next. */
    val oldestSealedBatch: SealedBatch?
        get() = sealedBatches.firstOrNull()

    /** The cheapest batch to send as a 401 re-probe. */
    val smallestSealedBatch: SealedBatch?
        get() = sealedBatches.minByOrNull { batch -> batch.byteCount }

    // Launch

    /** Creates the folders, seals every journal earlier launches left, and indexes the sealed batches. */
    @Throws(IOException::class)
    fun recoverAfterLaunch() {
        // A folder failure is reported at the end, after everything already on disk is recovered.
        var folderCreationError: IOException? = null
        try {
            createFoldersIfNeeded()
        } catch (creationError: IOException) {
            folderCreationError = creationError
        }

        // Half-written files from a crash mid-write hold nothing that is not elsewhere.
        deleteTemporaryFiles()

        // Seal whatever earlier launches journaled but never sealed, including after a crash.
        sealLeftoverJournals()

        // Index every sealed batch, oldest first.
        sealedBatches.clear()
        sealedBatches.addAll(batchFiles().mapNotNull { batchFile -> readIndexEntry(batchFile) })

        // A long offline period may have pushed the outbox over its cap.
        enforceCap()

        if (folderCreationError != null) {
            throw folderCreationError
        }
    }

    // Journal

    /** True when the journal must be sealed before this line: it already holds a full batch, or the line would overfill a request. */
    fun wouldJournalOverflow(eventLine: ByteArray): Boolean {
        if (journalEventCount == 0) {
            return false
        }

        val isJournalFull = journalEventCount >= limits.maxBatchEventCount
        val wouldExceedRequestBytes = journalByteCount + storedByteCount(eventLine) > limits.maxBatchBytes

        return isJournalFull || wouldExceedRequestBytes
    }

    /** Appends one encoded event line to the journal, so a crash from here on loses nothing. */
    @Throws(IOException::class)
    fun appendToJournal(eventLine: ByteArray) {
        // The folders may have been removed behind the SDK's back.
        createFoldersIfNeeded()

        // Every line ends in a newline, so the journal stays valid NDJSON as it grows.
        val lineWithNewline = ByteArray(eventLine.size + 1)
        System.arraycopy(eventLine, 0, lineWithNewline, 0, eventLine.size)
        lineWithNewline[eventLine.size] = NEWLINE_BYTE

        // Remember where this line starts, so a failed write can be cut back off.
        val lineStartOffset = journalFile.length()

        try {
            FileOutputStream(journalFile, true).use { journalStream -> journalStream.write(lineWithNewline) }
        } catch (writeError: IOException) {
            truncateQuietly(journalFile, lineStartOffset)
            throw writeError
        }

        // Count the line only once it is fully on disk.
        journalEventCount += 1
        journalByteCount += lineWithNewline.size
    }

    /** Turns the journal into a sealed batch with one atomic rename, then trims the outbox to its cap. */
    @Throws(IOException::class)
    fun sealJournal() {
        if (journalEventCount == 0) {
            return
        }

        // A journal removed behind the SDK's back leaves nothing to seal, so the counters restart.
        if (!journalFile.exists()) {
            logger.warning("The journal disappeared before sealing; $journalEventCount events are gone")
            journalEventCount = 0
            journalByteCount = 0
            return
        }

        // The batch folder may also have been removed, so recreate it before the rename.
        createFoldersIfNeeded()

        // A rename is atomic, so the events are always in exactly one file.
        val batchFile = makeBatchFile()
        if (!journalFile.renameTo(batchFile)) {
            throw IOException("Could not rename the journal to ${batchFile.name}")
        }

        // Index the new batch and start an empty journal.
        sealedBatches.add(SealedBatch(batchFile, journalEventCount, journalByteCount))
        journalEventCount = 0
        journalByteCount = 0

        enforceCap()
    }

    // Sealed batches

    /** Reads a batch body, or returns null (and forgets the batch) when its file has vanished; throws when it cannot be read. */
    @Throws(IOException::class)
    fun body(batch: SealedBatch): ByteArray? {
        // A missing file can never be sent, so it leaves the index.
        if (!batch.file.exists()) {
            sealedBatches.remove(batch)
            return null
        }

        return batch.file.readBytes()
    }

    /** Deletes a delivered batch. */
    fun delete(batch: SealedBatch) {
        sealedBatches.remove(batch)
        batch.file.delete()
    }

    /** Moves a batch out of the queue under an extra extension, keeping the file for inspection. */
    fun setAside(batch: SealedBatch, fileExtension: String) {
        sealedBatches.remove(batch)

        // If even the rename fails, the next launch indexes the file again and retries it.
        val setAsideFile = File(batch.file.parentFile, batch.file.name + "." + fileExtension)
        if (!batch.file.renameTo(setAsideFile)) {
            logger.error("Could not set aside ${batch.file.name}; the next launch tries it again")
        }
    }

    /** Replaces a batch the server called too large with its two halves, in the same queue position. */
    @Throws(IOException::class)
    fun splitInHalf(batch: SealedBatch, batchBody: ByteArray): BatchSplitOutcome {
        // The cap may have trimmed the batch while it was in flight.
        val batchIndex = sealedBatches.indexOf(batch)
        if (batchIndex < 0) {
            return BatchSplitOutcome.ALREADY_REMOVED
        }

        // A lone event that is still too large can never be accepted.
        val eventLines = lines(batchBody)
        if (eventLines.size <= 1) {
            delete(batch)
            return BatchSplitOutcome.DROPPED_LONE_EVENT
        }

        // Write both halves before removing the original, so a crash can only duplicate, never lose.
        val middleIndex = eventLines.size / 2
        val firstHalf = writeBatchFile(eventLines.subList(0, middleIndex), halfFile(batch, 1))
        val secondHalf = writeBatchFile(eventLines.subList(middleIndex, eventLines.size), halfFile(batch, 2))

        // Swap the original for its halves.
        sealedBatches.removeAt(batchIndex)
        sealedBatches.addAll(batchIndex, listOf(firstHalf, secondHalf))
        batch.file.delete()

        return BatchSplitOutcome.SPLIT
    }

    /** Deletes every journal and every batch file, for an opt-out. */
    fun deleteEverything() {
        // Remove the live journal and any set aside by an earlier failed recovery.
        for (journalFile in leftoverJournalFiles()) {
            journalFile.delete()
        }

        // Remove every batch file, indexed, unindexed or set aside.
        batchesDirectory.listFiles()?.forEach { batchFile -> batchFile.delete() }

        // Reset the in-memory view to match the empty folders.
        sealedBatches.clear()
        journalEventCount = 0
        journalByteCount = 0
    }

    // Private helpers

    // Creates the outbox and batch folders when missing.
    @Throws(IOException::class)
    private fun createFoldersIfNeeded() {
        if (batchesDirectory.isDirectory) {
            return
        }

        if (!batchesDirectory.mkdirs() && !batchesDirectory.isDirectory) {
            throw IOException("Could not create the outbox folder ${batchesDirectory.path}")
        }
    }

    // Deletes `.tmp` files a crash left mid-write.
    private fun deleteTemporaryFiles() {
        val temporaryFiles = (batchesDirectory.listFiles() ?: emptyArray()) + (directory.listFiles() ?: emptyArray())

        for (temporaryFile in temporaryFiles) {
            if (temporaryFile.name.endsWith(TEMPORARY_SUFFIX)) {
                temporaryFile.delete()
            }
        }
    }

    // Seals every journal earlier launches left: the live one, plus any set aside by a failed recovery.
    private fun sealLeftoverJournals() {
        for (leftoverJournalFile in leftoverJournalFiles()) {
            try {
                sealLeftoverJournal(leftoverJournalFile)
            } catch (sealError: IOException) {
                logger.error("Could not seal a leftover journal; keeping it for the next launch", sealError)

                // The live journal's path must be free, so this launch's appends never mix with old lines.
                if (leftoverJournalFile.name == JOURNAL_FILE_NAME) {
                    setLiveJournalAside()
                }
            }
        }
    }

    // Every journal file in the outbox folder, live or set aside, oldest name first.
    private fun leftoverJournalFiles(): List<File> {
        val folderContents = directory.listFiles() ?: return emptyList()

        return folderContents
            .filter { candidateFile -> candidateFile.name.startsWith(JOURNAL_FILE_PREFIX) && candidateFile.name.endsWith(BATCH_FILE_SUFFIX) }
            .sortedBy { journalFile -> journalFile.name }
    }

    // Seals one leftover journal into batches, dropping a line torn by a crash mid-append, then removes it.
    @Throws(IOException::class)
    private fun sealLeftoverJournal(leftoverJournalFile: File) {
        val journalBytes = leftoverJournalFile.readBytes()

        // A last line without its newline was cut short by a crash mid-write.
        val journalLines = lines(journalBytes).toMutableList()
        val hasTornLastLine = journalBytes.isNotEmpty() && journalBytes.last() != NEWLINE_BYTE
        if (hasTornLastLine) {
            journalLines.removeAt(journalLines.size - 1)
        }

        // Keep only complete JSON objects.
        val validEventLines = journalLines.filter { eventLine -> JsonReader.isJsonObject(String(eventLine, Charsets.UTF_8)) }
        val droppedLineCount = journalLines.size - validEventLines.size + (if (hasTornLastLine) 1 else 0)
        if (droppedLineCount > 0) {
            logger.warning("Discarded $droppedLineCount unreadable journal lines left by a previous launch")
        }

        // Re-chunk into batches that respect today's limits.
        for (batchLines in chunkIntoBatches(validEventLines)) {
            writeBatchFile(batchLines, makeBatchFile())
        }

        // Only now that every event is in a sealed batch is the journal safe to remove.
        if (!leftoverJournalFile.delete()) {
            throw IOException("Could not remove the sealed leftover journal")
        }
    }

    // Renames the live journal to a unique set-aside name, so a fresh journal starts and the next launch retries the old one.
    private fun setLiveJournalAside() {
        val setAsideFile = File(directory, "$JOURNAL_FILE_PREFIX-${makeSortableFileStamp()}$BATCH_FILE_SUFFIX")

        if (!journalFile.renameTo(setAsideFile)) {
            logger.error("Could not set the leftover journal aside; its events may be sent twice")
        }
    }

    // Every finished batch file, oldest first; set-aside files have another extension.
    private fun batchFiles(): List<File> {
        val folderContents = batchesDirectory.listFiles() ?: return emptyList()

        return folderContents
            .filter { candidateFile -> candidateFile.name.startsWith(BATCH_FILE_PREFIX) && candidateFile.name.endsWith(BATCH_FILE_SUFFIX) }
            .sortedBy { batchFile -> batchFile.name }
    }

    // Reads one batch file into its index entry, or returns null when it cannot be read right now.
    private fun readIndexEntry(batchFile: File): SealedBatch? {
        return try {
            val batchBody = batchFile.readBytes()
            SealedBatch(batchFile, lines(batchBody).size, batchBody.size)
        } catch (readError: IOException) {
            // An unreadable file is skipped and retried next launch.
            logger.error("Skipped an unreadable batch file ${batchFile.name}", readError)
            null
        }
    }

    // Drops the oldest sealed batches until the outbox is back under its event, byte and file caps.
    private fun enforceCap() {
        var droppedEventCount = 0
        var droppedBatchCount = 0

        // Totals are read once and counted down, so trimming many batches stays linear.
        var heldEventCount = totalEventCount
        var heldByteCount = totalByteCount

        // The journal is bounded by one batch, so trimming sealed batches always ends.
        while (droppedBatchCount < sealedBatches.size && isOverCap(heldEventCount, heldByteCount, sealedBatches.size - droppedBatchCount)) {
            val oldestBatch = sealedBatches[droppedBatchCount]
            oldestBatch.file.delete()

            heldEventCount -= oldestBatch.eventCount
            heldByteCount -= oldestBatch.byteCount
            droppedEventCount += oldestBatch.eventCount
            droppedBatchCount += 1
        }

        // Forget the dropped batches and say so once.
        if (droppedBatchCount > 0) {
            sealedBatches.subList(0, droppedBatchCount).clear()
        }

        if (droppedEventCount > 0) {
            logger.warning("Outbox over its cap; dropped the oldest $droppedEventCount unsent events")
        }
    }

    // True when any cap is exceeded.
    private fun isOverCap(heldEventCount: Int, heldByteCount: Long, heldBatchCount: Int): Boolean {
        val isOverEventCap = heldEventCount > limits.maxTotalEventCount
        val isOverByteCap = heldByteCount > limits.maxTotalBytes
        val isOverFileCap = heldBatchCount > limits.maxSealedBatchCount

        return isOverEventCap || isOverByteCap || isOverFileCap
    }

    // Groups event lines into batches under the event-count and byte limits.
    private fun chunkIntoBatches(eventLines: List<ByteArray>): List<List<ByteArray>> {
        val batches = ArrayList<List<ByteArray>>()
        var currentBatch = ArrayList<ByteArray>()
        var currentBatchBytes = 0

        for (eventLine in eventLines) {
            // Start a new batch when this line would break either limit.
            val lineBytes = storedByteCount(eventLine)
            val isCurrentBatchFull = currentBatch.size >= limits.maxBatchEventCount || currentBatchBytes + lineBytes > limits.maxBatchBytes
            if (currentBatch.isNotEmpty() && isCurrentBatchFull) {
                batches.add(currentBatch)
                currentBatch = ArrayList()
                currentBatchBytes = 0
            }

            currentBatch.add(eventLine)
            currentBatchBytes += lineBytes
        }

        // Keep the final partial batch.
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        return batches
    }

    // Writes event lines as one NDJSON batch file, atomically, and returns its index entry.
    @Throws(IOException::class)
    private fun writeBatchFile(eventLines: List<ByteArray>, batchFile: File): SealedBatch {
        createFoldersIfNeeded()

        // Every line, including the last, ends in a newline.
        val batchByteCount = eventLines.sumOf { eventLine -> storedByteCount(eventLine) }
        val batchBody = ByteArray(batchByteCount)
        var writeOffset = 0
        for (eventLine in eventLines) {
            System.arraycopy(eventLine, 0, batchBody, writeOffset, eventLine.size)
            writeOffset += eventLine.size
            batchBody[writeOffset] = NEWLINE_BYTE
            writeOffset += 1
        }

        // Write a temporary file, then rename it, so a crash leaves the whole file or none of it.
        val temporaryFile = File(batchFile.parentFile, batchFile.name + TEMPORARY_SUFFIX)
        FileOutputStream(temporaryFile).use { batchStream ->
            batchStream.write(batchBody)
            batchStream.fd.sync()
        }

        if (!temporaryFile.renameTo(batchFile)) {
            temporaryFile.delete()
            throw IOException("Could not write ${batchFile.name}")
        }

        return SealedBatch(batchFile, eventLines.size, batchBody.size)
    }

    // A unique stamp that sorts oldest first: 13-digit epoch milliseconds, then a 6-digit sequence.
    private fun makeSortableFileStamp(): String {
        fileNameSequence += 1

        return String.format(Locale.US, "%013d-%06d", clock.currentTimeMillis(), fileNameSequence)
    }

    // A batch file name that sorts oldest first and is not taken yet.
    private fun makeBatchFile(): File {
        while (true) {
            val batchFile = File(batchesDirectory, BATCH_FILE_PREFIX + makeSortableFileStamp() + BATCH_FILE_SUFFIX)

            if (!batchFile.exists()) {
                return batchFile
            }
        }
    }

    // The file for one half of a split batch, sorting right where the original did.
    private fun halfFile(batch: SealedBatch, halfNumber: Int): File {
        val originalStem = batch.file.name.removeSuffix(BATCH_FILE_SUFFIX)

        return File(batchesDirectory, "$originalStem-$halfNumber$BATCH_FILE_SUFFIX")
    }

    // Cuts a file back to a length, ignoring failure.
    private fun truncateQuietly(targetFile: File, length: Long) {
        try {
            RandomAccessFile(targetFile, "rw").use { randomAccessFile -> randomAccessFile.setLength(length) }
        } catch (truncateError: IOException) {
            logger.error("Could not cut a partial line off the journal", truncateError)
        }
    }
}
