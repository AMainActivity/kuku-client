package dev.messenger.feature.conversation

import dev.messenger.data.ManagedFiles
import java.io.File
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

class CaptureTakeTest {
    @Test fun discardAndLateCameraCallbackRemoveUnconfirmedFile() {
        val folder=kotlin.io.path.createTempDirectory("capture-test").toFile()
        try {
            val account=Uuid.random().toString()+":"+Uuid.random().toString()
            val take=CaptureTake(ManagedFiles(folder.absolutePath),account) {error("Must not attach")}
            val photo=take.allocate(CaptureKind.PHOTO)
            File(photo.path).writeBytes(byteArrayOf(1,2,3))
            take.close()
            assertFalse(File(photo.path).exists())
            // A camera callback can arrive after screen disposal.
            File(photo.path).writeBytes(byteArrayOf(4,5,6))
            take.complete(photo,true)
            assertFalse(File(photo.path).exists())
        } finally {folder.deleteRecursively()}
    }
    @Test fun confirmedAudioIsOwnedByDraftAndSurvivesCaptureDisposal() {
        val folder=kotlin.io.path.createTempDirectory("capture-test").toFile()
        try {
            val account=Uuid.random().toString()+":"+Uuid.random().toString()
            var attached:CapturedFile?=null
            var stages=0
            val take=CaptureTake(ManagedFiles(folder.absolutePath),account) {attached=it;stages++}
            val audio=take.allocate(CaptureKind.AUDIO)
            File(audio.path).writeBytes(byteArrayOf(1,2,3))
            take.complete(audio,true)
            assertTrue(take.ready)
            take.confirm()
            take.confirm()
            take.close()
            take.complete(audio,true)
            assertEquals(1,stages)
            assertEquals(audio,attached)
            assertEquals("audio/mp4",audio.mime)
            assertTrue(File(audio.path).exists())
        } finally {folder.deleteRecursively()}
    }
    @Test fun failedOrEmptyRecordingCannotBeAttached() {
        val folder=kotlin.io.path.createTempDirectory("capture-test").toFile()
        try {
            val account=Uuid.random().toString()+":"+Uuid.random().toString()
            val take=CaptureTake(ManagedFiles(folder.absolutePath),account) {error("Must not attach")}
            val audio=take.allocate(CaptureKind.AUDIO)
            File(audio.path).writeBytes(byteArrayOf())
            take.complete(audio,true)
            assertFalse(take.ready)
            assertTrue(take.failed)
            take.confirm()
            assertFalse(File(audio.path).exists())
        } finally {folder.deleteRecursively()}
    }
}