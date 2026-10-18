package com.xkdlabs.simpledatalogs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The disk outbox: journal, sealing, caps, splitting and crash recovery. */
class EventOutboxTest {
    /** A fresh folder per test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Collects log lines. */
    private val logger = RecordingLogger()

    /** Builds an outbox with small limits. */
    private fun makeOutbox(
        directory: File = temporaryFolder.root,
        maxBatchEventCount: Int = 50,
        maxBatchBytes: Int = 256_000,
        maxTotalEventCount: Int = 10_000,
        maxTotalBytes: Long = 10L * 1024 * 1024,
        maxSealedBatchCount: Int = 500,
    ): EventOutbox {
        val limits = OutboxLimits(maxBatchEventCount, maxBatchBytes, maxTotalEventCount, maxTotalBytes, maxSealedBatchCount)
        val outbox = EventOutbox(directory, limits, ManualClock(), logger)
        outbox.recoverAfterLaunch()

        return outbox
    }

    /** A small valid event line. */
    private fun eventLine(eventNumber: Int): ByteArray {
        return """{"event":"e$eventNumber","event_id":"id-$eventNumber","ts":"2026-10-12T09:14:03.120Z","props":{}}""".toByteArray()
    }

    /** The event names in a body, in order. */
    private fun eventNames(body: ByteArray): List<String> {
        return EventOutbox.lines(body).map { line -> (JsonReader.parse(String(line)) as Map<*, *>)["event"] as String }
    }

    @Test
    fun sealingRenamesTheJournalToASortableBatchFile() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.appendToJournal(eventLine(2))

        outbox.sealJournal()

        val sealedBatch = outbox.oldestSealedBatch!!
        assertTrue(sealedBatch.file.name, sealedBatch.file.name.matches(Regex("batch-\\d{13}-\\d{6}\\.ndjson")))
        assertEquals(2, sealedBatch.eventCount)
        assertFalse(outbox.journalFile.exists())
        assertEquals(listOf("e1", "e2"), eventNames(outbox.body(sealedBatch)!!))
        assertTrue(outbox.body(sealedBatch)!!.last() == '\n'.code.toByte())
    }

    @Test
    fun journalOverflowsAtTheEventAndByteLimits() {
        val outbox = makeOutbox(maxBatchEventCount = 2, maxBatchBytes = eventLine(1).size * 3 + 3)
        assertFalse(outbox.wouldJournalOverflow(eventLine(1)))

        outbox.appendToJournal(eventLine(1))
        assertFalse(outbox.wouldJournalOverflow(eventLine(2)))

        outbox.appendToJournal(eventLine(2))
        assertTrue(outbox.wouldJournalOverflow(eventLine(3)))

        // A huge line overflows by bytes even when the count is fine.
        val byteLimitedOutbox = makeOutbox(directory = temporaryFolder.newFolder(), maxBatchBytes = 200)
        byteLimitedOutbox.appendToJournal(eventLine(1))
        assertTrue(byteLimitedOutbox.wouldJournalOverflow(ByteArray(150) { 'x'.code.toByte() }))
    }

    @Test
    fun capsDropTheOldestBatchesFirst() {
        val outbox = makeOutbox(maxTotalEventCount = 4)

        // Three sealed batches of two events each; the cap of four keeps the newest two.
        for (batchNumber in 0 until 3) {
            outbox.appendToJournal(eventLine(batchNumber * 2))
            outbox.appendToJournal(eventLine(batchNumber * 2 + 1))
            outbox.sealJournal()
        }

        val keptNames = outbox.sealedBatchSnapshot.flatMap { batch -> eventNames(outbox.body(batch)!!) }
        assertEquals(listOf("e2", "e3", "e4", "e5"), keptNames)
        assertTrue(logger.contains("dropped the oldest 2 unsent events"))
        assertEquals(2, outbox.batchesDirectory.listFiles()!!.size)
    }

    @Test
    fun fileAndByteCapsAlsoTrim() {
        val fileCappedOutbox = makeOutbox(maxSealedBatchCount = 2)
        for (eventNumber in 0 until 5) {
            fileCappedOutbox.appendToJournal(eventLine(eventNumber))
            fileCappedOutbox.sealJournal()
        }

        assertEquals(2, fileCappedOutbox.sealedBatchSnapshot.size)

        val byteCappedOutbox = makeOutbox(directory = temporaryFolder.newFolder(), maxTotalBytes = (eventLine(0).size + 1) * 3L)
        for (eventNumber in 0 until 5) {
            byteCappedOutbox.appendToJournal(eventLine(eventNumber))
            byteCappedOutbox.sealJournal()
        }

        assertEquals(3, byteCappedOutbox.totalEventCount)
    }

    @Test
    fun splittingReplacesABatchWithTwoHalvesInTheSamePlace() {
        val outbox = makeOutbox()
        for (eventNumber in 0 until 5) {
            outbox.appendToJournal(eventLine(eventNumber))
        }
        outbox.sealJournal()
        outbox.appendToJournal(eventLine(99))
        outbox.sealJournal()

        val originalBatch = outbox.oldestSealedBatch!!
        val outcome = outbox.splitInHalf(originalBatch, outbox.body(originalBatch)!!)

        assertEquals(BatchSplitOutcome.SPLIT, outcome)
        assertFalse(originalBatch.file.exists())

        // The halves come first, in order, and their names sort where the original did.
        val batches = outbox.sealedBatchSnapshot
        assertEquals(listOf(listOf("e0", "e1"), listOf("e2", "e3", "e4"), listOf("e99")), batches.map { batch -> eventNames(outbox.body(batch)!!) })
        assertEquals(batches.map { batch -> batch.file.name }.sorted(), batches.map { batch -> batch.file.name })
        assertTrue(batches[0].file.name.endsWith("-1.ndjson"))
    }

    @Test
    fun splittingALoneEventDropsIt() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.sealJournal()
        val loneBatch = outbox.oldestSealedBatch!!

        assertEquals(BatchSplitOutcome.DROPPED_LONE_EVENT, outbox.splitInHalf(loneBatch, outbox.body(loneBatch)!!))
        assertNull(outbox.oldestSealedBatch)
        assertFalse(loneBatch.file.exists())
    }

    @Test
    fun recoverySealsALeftoverJournalAndDropsATornLastLine() {
        val firstLaunch = makeOutbox()
        firstLaunch.appendToJournal(eventLine(1))
        firstLaunch.appendToJournal(eventLine(2))

        // A crash mid-append leaves half a line, and an older bug left a non-JSON line.
        firstLaunch.journalFile.appendText("not json\n")
        firstLaunch.journalFile.appendText("""{"event":"e3","event_id":"id-3","ts":"2026""")

        val secondLaunch = makeOutbox()

        assertFalse(secondLaunch.journalFile.exists())
        assertEquals(2, secondLaunch.totalEventCount)
        assertEquals(listOf("e1", "e2"), eventNames(secondLaunch.body(secondLaunch.oldestSealedBatch!!)!!))
        assertTrue(logger.contains("Discarded 2 unreadable journal lines"))
    }

    @Test
    fun recoveryRechunksAnOversizedJournalAndIndexesExistingBatches() {
        val firstLaunch = makeOutbox()
        firstLaunch.appendToJournal(eventLine(0))
        firstLaunch.sealJournal()

        // A journal of five events, recovered with a batch limit of two.
        for (eventNumber in 1..5) {
            firstLaunch.appendToJournal(eventLine(eventNumber))
        }

        val secondLaunch = makeOutbox(maxBatchEventCount = 2)

        assertEquals(listOf(1, 2, 2, 1), secondLaunch.sealedBatchSnapshot.map { batch -> batch.eventCount })
        assertEquals(6, secondLaunch.totalEventCount)
    }

    @Test
    fun recoveryRemovesHalfWrittenTemporaryFiles() {
        val firstLaunch = makeOutbox()
        val temporaryFile = File(firstLaunch.batchesDirectory, "batch-0000000000001-000001.ndjson.tmp")
        temporaryFile.writeText("{\"event\":")

        val secondLaunch = makeOutbox()

        assertFalse(temporaryFile.exists())
        assertNull(secondLaunch.oldestSealedBatch)
    }

    @Test
    fun setAsideFilesLeaveTheQueueAndAreNotReindexed() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.sealJournal()
        val batch = outbox.oldestSealedBatch!!

        outbox.setAside(batch, EventOutbox.UNSUPPORTED_EXTENSION)

        assertNull(outbox.oldestSealedBatch)
        assertTrue(File(batch.file.path + ".unsupported").exists())
        assertNull(makeOutbox().oldestSealedBatch)
    }

    @Test
    fun aVanishedBatchFileIsForgotten() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.sealJournal()
        val batch = outbox.oldestSealedBatch!!
        batch.file.delete()

        assertNull(outbox.body(batch))
        assertNull(outbox.oldestSealedBatch)
    }

    @Test
    fun deleteEverythingEmptiesTheFolders() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.sealJournal()
        outbox.appendToJournal(eventLine(2))

        outbox.deleteEverything()

        assertEquals(0, outbox.totalEventCount)
        assertFalse(outbox.journalFile.exists())
        assertEquals(0, outbox.batchesDirectory.listFiles()!!.size)
    }

    @Test
    fun smallestBatchIsTheProbeCandidate() {
        val outbox = makeOutbox()
        outbox.appendToJournal(eventLine(1))
        outbox.appendToJournal(eventLine(2))
        outbox.sealJournal()
        outbox.appendToJournal(eventLine(3))
        outbox.sealJournal()

        assertEquals(1, outbox.smallestSealedBatch!!.eventCount)
    }
}
