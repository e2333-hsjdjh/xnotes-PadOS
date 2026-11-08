package com.xnotes.gl

import android.os.SharedMemory
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64

/** Real producer/queue/record tests. No GL context, texture decoding or pixel rendering is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasSceneQueuedImageTest {
    @Test fun queuedImageGeometryIsCapturedBeforeTheConsumerDrainsIt() = withScene { f ->
        val item = ImageItem(f.image(), Rect(8.0, 12.0, 40.0, 30.0))
        val expected = values(item)
        val bounds = item.paintBounds()
        f.scene.upsertImage(item, bounds)

        item.rect = Rect(100.0, 120.0, 70.0, 50.0)
        item.orientation = 90
        item.angle = .75
        item.flip = Flip.XY
        item.part = Rect(.25, .125, .5, .75)
        f.drain()

        val record = f.record(item)
        assertSame(item, field<CanvasItem>(record, "item"))
        assertEquals(bounds, field<Rect>(record, "bounds"))
        assertValues(expected, field(record, "image"))
        assertEquals(1, f.scene.itemCount)
    }

    @Test fun queuedSourceStaysBorrowedAndOnlyAnExplicitUpsertAdoptsItsReplacement() = withScene { f ->
        val original = f.image()
        val replacement = f.image()
        val item = ImageItem(original, Rect(8.0, 12.0, 40.0, 30.0))
        f.scene.upsertImage(item, item.paintBounds())
        item.image = replacement
        f.drain()

        assertSame(original, values(field<Any>(f.record(item), "image")).source)
        assertTrue("the scene borrows the original file", original.file.isFile)
        assertTrue(replacement.file.isFile)

        f.scene.upsertImage(item, item.paintBounds())
        item.image = original
        f.drain()

        assertSame(replacement, values(field<Any>(f.record(item), "image")).source)
        assertTrue("replacing a scene source does not delete its borrowed file", original.file.isFile)
        assertTrue(replacement.file.isFile)
        assertEquals(1, f.scene.itemCount)
    }

    @Test fun laterUpsertCapturesNewValuesWithoutMutatingThePreviouslyConsumedDrawing() = withScene { f ->
        val item = ImageItem(f.image(), Rect(8.0, 12.0, 40.0, 30.0))
        val first = values(item)
        f.scene.upsertImage(item, item.paintBounds())
        f.drain()
        val previousRecord = f.record(item)
        val previousDrawing = field<Any>(previousRecord, "image")
        val previousZ = field<Int>(previousRecord, "z")

        item.image = f.image()
        item.rect = Rect(24.0, 36.0, 60.0, 45.0)
        item.orientation = 270
        item.angle = -.4
        item.flip = Flip.Y
        item.part = Rect(.1, .2, .8, .6)
        val second = values(item)
        val secondBounds = item.paintBounds()
        f.scene.upsertImage(item, secondBounds)

        // Mutate again before the second drain. Both records must retain their own publication.
        item.image = first.source
        item.rect = Rect(-30.0, -40.0, 12.0, 16.0)
        item.orientation = 180
        item.angle = 1.25
        item.flip = Flip.NONE
        item.part = null
        f.drain()

        assertValues(first, previousDrawing)
        val record = f.record(item)
        assertSame(item, field<CanvasItem>(record, "item"))
        assertEquals(previousZ, field<Int>(record, "z"))
        assertValues(second, field(record, "image"))
        assertEquals(secondBounds, field<Rect>(record, "bounds"))
        assertEquals(1, f.scene.itemCount)
    }

    @Test fun imageAndStrokeUpsertsFreezeBothNullAndNonNullSyncMarks() = withScene { f ->
        val image = ImageItem(f.image(), Rect(8.0, 12.0, 40.0, 30.0))
        val stroke = strokeAt(60.0)
        val mesh = checkNotNull(ItemMesher.mesh(stroke))
        val mark = SyncMark(7, 240)
        for (expected in listOf(null, mark)) {
            image.sync = expected
            stroke.sync = expected
            f.scene.upsertImage(image, image.paintBounds())
            f.scene.upsert(stroke, mesh.parts, mesh.bounds)
            image.sync = if (expected == null) mark else null
            stroke.sync = image.sync

            f.drain()

            assertEquals("image upsert froze $expected", expected, field<SyncMark?>(f.record(image), "sync"))
            assertEquals("stroke upsert froze $expected", expected, field<SyncMark?>(f.record(stroke), "sync"))
            assertSame(image, field<CanvasItem>(f.record(image), "item"))
            assertSame(stroke, field<CanvasItem>(f.record(stroke), "item"))
            assertEquals(2, f.scene.itemCount)
        }
    }

    @Test fun syncChangedOwnsItsMarksAndListWithoutRebuildingGeometry() = withScene { f ->
        val image = ImageItem(f.image(), Rect(8.0, 12.0, 40.0, 30.0))
        val stroke = strokeAt(60.0)
        val outsider = strokeAt(90.0).apply { sync = SyncMark(9, 10) }
        f.scene.upsertImage(image, image.paintBounds())
        for (item in listOf(stroke, outsider)) {
            val mesh = checkNotNull(ItemMesher.mesh(item))
            f.scene.upsert(item, mesh.parts, mesh.bounds)
        }
        f.drain()
        val items = listOf<CanvasItem>(image, stroke, outsider)
        val records = items.associateWith { recordGeometry(f.record(it)) }
        val drawing = field<Any>(f.record(image), "image")
        val imageValues = values(drawing)
        val store = field<GeometryStore>(f.scene, "store")
        val vertices = store.usedVertices
        val indices = store.usedIndices
        assertTrue(vertices > 0 && indices > 0)

        val mark = SyncMark(7, 240)
        image.sync = mark
        stroke.sync = mark
        val touched = mutableListOf<CanvasItem>(image, stroke)
        f.scene.syncChanged(touched)
        touched.clear()
        touched += outsider
        outsider.sync = SyncMark(9, 999)
        image.sync = null
        stroke.sync = null
        image.rect = Rect(500.0, 600.0, 80.0, 70.0)
        image.orientation = 90
        stroke.translate(1000.0, -200.0)
        f.drain()

        assertEquals(mark, field<SyncMark?>(f.record(image), "sync"))
        assertEquals(mark, field<SyncMark?>(f.record(stroke), "sync"))
        assertEquals("later list edits cannot redirect metadata", SyncMark(9, 10), field<SyncMark?>(f.record(outsider), "sync"))
        assertNotEquals(records.getValue(stroke).bounds, stroke.paintBounds())
        assertSame(drawing, field<Any>(f.record(image), "image"))
        assertValues(imageValues, drawing)
        records.forEach { (item, before) -> assertRecordGeometry(before, f.record(item)) }
        assertEquals(vertices, store.usedVertices)
        assertEquals(indices, store.usedIndices)

        // A null mark is also a frozen update, not permission to read a later live-model mark.
        f.scene.syncChanged(listOf(stroke))
        stroke.sync = mark
        f.drain()
        assertNull(field<SyncMark?>(f.record(stroke), "sync"))
        records.forEach { (item, before) -> assertRecordGeometry(before, f.record(item)) }
        assertEquals(vertices, store.usedVertices)
        assertEquals(indices, store.usedIndices)
    }

    @Test fun syncNeverRevivesMissingRecordsAndLaterUpsertSuppliesItsOwnMark() = withScene { f ->
        val removed = strokeAt(30.0)
        val notYetCommitted = strokeAt(60.0)
        val removedMesh = checkNotNull(ItemMesher.mesh(removed))
        f.scene.upsert(removed, removedMesh.parts, removedMesh.bounds)
        f.drain()
        assertEquals(1, f.scene.itemCount)

        removed.sync = SyncMark(7, 100)
        notYetCommitted.sync = removed.sync
        f.scene.remove(removed)
        f.scene.syncChanged(listOf(removed, notYetCommitted))
        f.drain()
        assertNull(f.recordOrNull(removed))
        assertNull(f.recordOrNull(notYetCommitted))
        assertEquals(0, f.scene.itemCount)
        val store = field<GeometryStore>(f.scene, "store")
        assertEquals(0, store.usedVertices)
        assertEquals(0, store.usedIndices)

        // An earlier message for an absent item cannot replace the later upsert's own snapshot.
        notYetCommitted.sync = SyncMark(7, 200)
        f.scene.syncChanged(listOf(notYetCommitted))
        val upsertMark = SyncMark(7, 300)
        notYetCommitted.sync = upsertMark
        val mesh = checkNotNull(ItemMesher.mesh(notYetCommitted))
        f.scene.upsert(notYetCommitted, mesh.parts, mesh.bounds)
        notYetCommitted.sync = null
        f.drain()

        assertEquals(upsertMark, field<SyncMark?>(f.record(notYetCommitted), "sync"))
        assertNull(f.recordOrNull(removed))
        assertEquals(1, f.scene.itemCount)
        assertTrue(store.usedVertices > 0 && store.usedIndices > 0)
    }

    private data class RecordGeometry(val record: Any, val parts: List<Any>, val bounds: Rect, val z: Int)

    private fun recordGeometry(record: Any) = RecordGeometry(
        record, field(record, "parts"), field(record, "bounds"), field(record, "z"),
    )

    private fun assertRecordGeometry(before: RecordGeometry, record: Any) {
        assertSame(before.record, record)
        assertSame(before.parts, field<List<Any>>(record, "parts"))
        assertEquals(before.bounds, field<Rect>(record, "bounds"))
        assertEquals(before.z, field<Int>(record, "z"))
    }

    private fun strokeAt(y: Double) = Stroke(
        Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 6.0),
    ).apply {
        addSample(Sample(8.0, y, 1.0, 0.0))
        addSample(Sample(56.0, y, 1.0, 10.0))
    }

    private data class Values(
        val source: ImageData,
        val rect: Rect,
        val orientation: Int,
        val angle: Double,
        val flip: Flip,
        val part: Rect?,
    )

    // The old consumer stores ImageItem; the fixed consumer stores immutable drawing values.
    // Reading their common fields keeps the baseline compilable and fails on content, not API shape.
    private fun values(drawing: Any) = Values(
        if (drawing is ImageItem) drawing.image else field(drawing, "source"),
        field(drawing, "rect"), field(drawing, "orientation"), field(drawing, "angle"),
        field(drawing, "flip"), field(drawing, "part"),
    )

    private fun assertValues(expected: Values, drawing: Any) {
        val actual = values(drawing)
        assertSame("encoded source", expected.source, actual.source)
        assertEquals("rect", expected.rect, actual.rect)
        assertEquals("orientation", expected.orientation, actual.orientation)
        assertEquals("angle", expected.angle, actual.angle, 0.0)
        assertEquals("flip", expected.flip, actual.flip)
        assertEquals("part", expected.part, actual.part)
    }

    private class Fixture {
        val scene = CanvasScene()
        private val files = mutableListOf<File>()

        init { scene.decodeOn = { error("Queue-only fixture must not schedule image decoding") } }

        fun image(): ImageData {
            val file = File.createTempFile("queued-image-", ".png", RuntimeEnvironment.getApplication().cacheDir)
            files += file
            // A real one-pixel PNG belongs only to this fixture; the queue does not read its bytes.
            file.writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
            ))
            return ImageData(file, 1, 1)
        }

        fun drain() {
            CanvasScene::class.java.getDeclaredMethod("drainEdits").apply { isAccessible = true }.invoke(scene)
        }

        fun recordOrNull(item: CanvasItem): Any? = field<Map<CanvasItem, Any>>(scene, "records")[item]

        fun record(item: CanvasItem): Any = checkNotNull(recordOrNull(item))

        fun close() {
            try {
                // GeometryStore allocates these CPU mirrors even though no GL work was requested.
                for (name in listOf("store", "wetStore", "tailStore")) {
                    val store = field<GeometryStore>(scene, name)
                    val mapped = field<MutableSet<ByteBuffer>>(store, "mapped")
                    mapped.toList().forEach(SharedMemory::unmap)
                    mapped.clear()
                }
            } finally { files.forEach { it.delete() } }
        }
    }

    private fun withScene(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> field(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
    }
}
