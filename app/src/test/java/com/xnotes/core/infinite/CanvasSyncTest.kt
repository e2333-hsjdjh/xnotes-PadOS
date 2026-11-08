package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.history.UnlinkSync
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.SyncMark
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CanvasSyncTest {
    private fun box(): ImageItem =
        ImageItem(ImageData(File("unused"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))

    /** Equal values deliberately exercise the document's reference-identity contract. */
    private class EqualBox(private val value: Int, private val item: ImageItem) : CanvasItem by item {
        override fun equals(other: Any?): Boolean = other is EqualBox && value == other.value
        override fun hashCode(): Int = value
    }

    private class GuardedBox(private val item: ImageItem) : CanvasItem by item {
        var rejectBounds = false
        override fun bounds(): Rect {
            check(!rejectBounds) { "Sync notification must not read geometry" }
            return item.bounds()
        }
        override fun paintBounds(): Rect = bounds()
    }

    @Test fun syncNotificationFiltersMembershipAndDeduplicatesByIdentityInInputOrder() {
        val doc = InfiniteDocument()
        val first = EqualBox(7, box())
        val second = EqualBox(7, box())
        val outsider = EqualBox(7, box())
        val removed = EqualBox(7, box())
        assertEquals(first, second)
        assertEquals(first, outsider)
        doc.addAll(listOf(first, second, removed))
        doc.remove(removed)
        val notifications = mutableListOf<List<CanvasItem>>()
        doc.listener = object : InfiniteDocument.Listener {
            override fun onSyncChanged(items: List<CanvasItem>) { notifications.add(items) }
        }

        doc.syncChanged(listOf(outsider, second, second, removed, first, second))

        assertEquals(1, notifications.size)
        assertEquals(2, notifications.single().size)
        assertSame(second, notifications.single()[0])
        assertSame(first, notifications.single()[1])
        doc.syncChanged(emptyList())
        doc.syncChanged(listOf(outsider, removed, outsider))
        assertEquals(1, notifications.size)
    }

    @Test fun syncNotificationDoesNotReadGeometryOrInvalidateCachedBounds() {
        val doc = InfiniteDocument()
        val item = GuardedBox(box())
        doc.add(item)
        val bounds = doc.contentBounds()
        val events = mutableListOf<String>()
        doc.listener = object : InfiniteDocument.Listener {
            override fun onItemChanged(item: CanvasItem) { events.add("geometry") }
            override fun onOrderChanged() { events.add("order") }
            override fun onReset() { events.add("reset") }
            override fun onSyncChanged(items: List<CanvasItem>) { events.add("sync") }
        }
        item.rejectBounds = true
        item.sync = SyncMark(4, 120)

        doc.syncChanged(listOf(item))

        assertEquals(listOf("sync"), events)
        assertEquals(1, doc.index.size)
        assertSame(bounds, doc.contentBounds())
    }

    @Test fun notificationListsAreIndependentOfCallerMutationAndLaterNotifications() {
        val doc = InfiniteDocument()
        val first = box()
        val second = box()
        doc.addAll(listOf(first, second))
        val notifications = mutableListOf<List<CanvasItem>>()
        doc.listener = object : InfiniteDocument.Listener {
            override fun onSyncChanged(items: List<CanvasItem>) { notifications.add(items) }
        }
        val changed = mutableListOf<CanvasItem>(first, first)
        doc.syncChanged(changed)
        changed.clear()
        changed.add(second)
        doc.syncChanged(changed)
        changed.clear()

        assertEquals(2, notifications.size)
        assertSame(first, notifications[0].single())
        assertSame(second, notifications[1].single())
    }

    @Test fun unlinkAndHistoryUndoRedoNotifyTheValuesAfterEachMutation() {
        val doc = InfiniteDocument()
        val firstMark = SyncMark(4, 120)
        val secondMark = SyncMark(4, 260)
        val first = box().apply { sync = firstMark }
        val second = box().apply { sync = secondMark }
        doc.addAll(listOf(first, second))
        val notifications = mutableListOf<List<Pair<CanvasItem, SyncMark?>>>()
        var geometryChanges = 0
        doc.listener = object : InfiniteDocument.Listener {
            override fun onItemChanged(item: CanvasItem) { geometryChanges++ }
            override fun onSyncChanged(items: List<CanvasItem>) {
                notifications.add(items.map { it to it.sync })
            }
        }
        val command = OnCanvasSync(
            doc, UnlinkSync(listOf(first to firstMark, second to secondMark)), listOf(first, second),
        )
        val history = History()

        command.redo()
        history.push(command)
        history.undo()
        history.redo()

        assertEquals(3, notifications.size)
        assertEquals(listOf(first to null, second to null), notifications[0])
        assertEquals(listOf(first to firstMark, second to secondMark), notifications[1])
        assertEquals(listOf(first to null, second to null), notifications[2])
        assertEquals(0, geometryChanges)
    }

    @Test fun syncCommandKeepsItsOriginalTargetsWhenCallerChangesTheList() {
        val doc = InfiniteDocument()
        val mark = SyncMark(4, 120)
        val first = box().apply { sync = mark }
        val second = box()
        doc.addAll(listOf(first, second))
        val notifications = mutableListOf<List<CanvasItem>>()
        doc.listener = object : InfiniteDocument.Listener {
            override fun onSyncChanged(items: List<CanvasItem>) { notifications.add(items) }
        }
        val touched = mutableListOf<CanvasItem>(first)
        val command = OnCanvasSync(doc, UnlinkSync(listOf(first to mark)), touched)
        touched.clear()
        touched.add(second)

        command.redo()
        command.undo()

        assertEquals(2, notifications.size)
        assertSame(first, notifications[0].single())
        assertSame(first, notifications[1].single())
        assertEquals(mark, first.sync)
    }

    @Test fun syncCommandDoesNotNotifyTargetsRemovedBeforeUndo() {
        val doc = InfiniteDocument()
        val mark = SyncMark(4, 120)
        val item = box().apply { sync = mark }
        doc.add(item)
        var notifications = 0
        doc.listener = object : InfiniteDocument.Listener {
            override fun onSyncChanged(items: List<CanvasItem>) { notifications++ }
        }
        val command = OnCanvasSync(doc, UnlinkSync(listOf(item to mark)), listOf(item))
        command.redo()
        doc.remove(item)

        command.undo()

        assertEquals(mark, item.sync)
        assertEquals(1, notifications)
        assertTrue(doc.isEmpty)
    }
}
