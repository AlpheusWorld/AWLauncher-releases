package ru.aw.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.aw.launcher.ui.screens.*
import ru.aw.launcher.ui.theme.*

class SkinSectionCollapseTest {
    @Test
    fun `scrolled final pack can be collapsed through its pinned heading without jumping to a previous pack`() {
        val grid = LazyGridState()
        var expanded by mutableStateOf(true)
        var anchor by mutableStateOf<String?>(null)
        var offset by mutableIntStateOf(0)
        val scene = ImageComposeScene(300, 240, density = Density(1f)) {
            AWTheme {
                val headings = listOf(SkinGridHeading("before", "Earlier pack", 0, 6, false),
                    SkinGridHeading("last", "Dungeons II", 7, if (expanded) 21 else 0, expanded))
                val toggle: (SkinGridHeading) -> Unit = { heading ->
                    offset = anchorSkinHeading(grid, heading)
                    anchor = heading.key
                    expanded = !expanded
                }
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = skinSectionTailPadding(headings, anchor, 240f, 100f, 3, offset.toFloat()).dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item("before", span = { GridItemSpan(3) }) { SkinSectionTitle("Earlier pack", false) {} }
                        items(6, key = { "before-$it" }) { Spacer(Modifier.height(100.dp)) }
                        item("last", span = { GridItemSpan(3) }) { SkinSectionTitle("Dungeons II", expanded) { toggle(headings.last()) } }
                        if (expanded) items(21, key = { "skin-$it" }) { Spacer(Modifier.height(100.dp).background(AWColors.SurfaceHigh)) }
                    }
                    SkinPinnedHeading(grid, headings, toggle)
                }
            }
        }
        var time = 0L
        fun frames() { repeat(8) { time += 16_666_667; scene.render(time).close() } }
        try {
            frames()
            grid.requestScrollToItem(19)
            frames()
            assertTrue(grid.firstVisibleItemIndex > 7)
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 22f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 22f))
            frames()
            assertFalse(expanded, "The pinned heading must close the pack that owns the scrolled rows")
            val header = grid.layoutInfo.visibleItemsInfo.first { it.key == "last" }
            assertEquals(0, header.offset.y, "The collapsed final header must stay at the top, rather than backfill previous skins")
            assertTrue(grid.layoutInfo.visibleItemsInfo.none { it.key.toString().startsWith("skin-") })
        } finally { scene.close() }
    }

    @Test
    fun `an inline heading keeps its original position when its rows disappear`() {
        val grid = LazyGridState()
        var expanded by mutableStateOf(true)
        var offset by mutableIntStateOf(0)
        var anchor by mutableStateOf<String?>(null)
        val scene = ImageComposeScene(300, 400, density = Density(1f)) {
            AWTheme {
                val heading = SkinGridHeading("last", "Final pack", 1, if (expanded) 12 else 0, expanded)
                LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = skinSectionTailPadding(listOf(heading), anchor, 400f, 100f, 3, offset.toFloat()).dp)) {
                    item("prefix", span = { GridItemSpan(3) }) { Spacer(Modifier.height(120.dp)) }
                    item("last", span = { GridItemSpan(3) }) { SkinSectionTitle("Final pack", expanded) {
                        offset = anchorSkinHeading(grid, heading); anchor = heading.key; expanded = false
                    } }
                    if (expanded) items(12) { Spacer(Modifier.height(100.dp)) }
                }
            }
        }
        var time = 0L
        fun frames() { repeat(8) { time += 16_666_667; scene.render(time).close() } }
        try {
            frames()
            val before = grid.layoutInfo.visibleItemsInfo.first { it.key == "last" }.offset.y
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, before + 22f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, before + 22f))
            frames()
            assertFalse(expanded)
            assertEquals(before, grid.layoutInfo.visibleItemsInfo.first { it.key == "last" }.offset.y)
        } finally { scene.close() }
    }
}
