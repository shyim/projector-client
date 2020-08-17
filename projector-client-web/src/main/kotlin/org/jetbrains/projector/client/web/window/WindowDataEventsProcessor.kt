/*
 * MIT License
 *
 * Copyright (c) 2019-2020 JetBrains s.r.o.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jetbrains.projector.client.web.window

import org.jetbrains.projector.client.common.SingleRenderingSurfaceProcessor.Companion.shrinkByPaintEvents
import org.jetbrains.projector.client.common.misc.ImageCacher
import org.jetbrains.projector.client.common.misc.Logger
import org.jetbrains.projector.client.common.misc.ParamsProvider
import org.jetbrains.projector.common.misc.firstNotNullOrNull
import org.jetbrains.projector.common.protocol.data.ImageId
import org.jetbrains.projector.common.protocol.toClient.ServerWindowEvent
import org.jetbrains.projector.common.protocol.toClient.ServerWindowSetChangedEvent
import org.jetbrains.projector.common.protocol.toClient.WindowData
import org.jetbrains.projector.common.protocol.toClient.WindowType
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLLinkElement
import kotlin.browser.document
import kotlin.collections.isNotEmpty

class WindowDataEventsProcessor(private val windowManager: WindowManager) {

  private var excludedWindowIds = emptyList<Int>()

  @OptIn(ExperimentalStdlibApi::class)
  fun redrawWindows() {
    synchronized(windowManager) {
      windowManager.forEach(Window::drawBufferedEvents)
    }
  }

  fun onClose() {
    process(ServerWindowSetChangedEvent(emptyList()))
  }

  fun process(windowDataEvents: ServerWindowSetChangedEvent) {
    val excludedWindows = when (val selectedId = ParamsProvider.IDE_WINDOW_ID) {
      null -> emptyList()

      else -> windowDataEvents.windowDataList
        .filter { it.windowType == WindowType.IDEA_WINDOW }
        .sortedBy(WindowData::id)
        .filterIndexed { index, _ -> index != selectedId }
    }
    excludedWindowIds = excludedWindows.map(WindowData::id)
    val presentedWindows = windowDataEvents.windowDataList.subtract(excludedWindows)

    removeAbsentWindows(presentedWindows)

    synchronized(windowManager) {
      presentedWindows.forEach { event ->
        val window = windowManager.getOrCreate(event)

        event.cursorType?.let { window.cursorType = it }
        window.title = event.title
        window.isShowing = event.isShowing
        window.bounds = event.bounds
        window.zIndex = (event.zOrder - presentedWindows.size) * WindowManager.zIndexStride
      }
    }

    setTitle(presentedWindows)
    setFavIcon(presentedWindows)
  }

  private fun setTitle(presentedWindows: Iterable<WindowData>) {
    val topmostWindowTitle = presentedWindows
      .filter(WindowData::isShowing)
      .sortedByDescending(WindowData::zOrder)
      .firstNotNullOrNull(WindowData::title)

    document.title = topmostWindowTitle ?: DEFAULT_TITLE
  }

  private fun setFavIcon(presentedWindows: Iterable<WindowData>) {
    val topmostWindowIconIds = presentedWindows
      .filter(WindowData::isShowing)
      .sortedByDescending(WindowData::zOrder)
      .mapNotNull(WindowData::icons)
      .firstOrNull(List<*>::isNotEmpty)

    fun selectIcon(icons: List<ImageId>?) = icons?.firstOrNull()  // todo

    val selectedIconId = selectIcon(topmostWindowIconIds)

    val selectedIconUrl = when (val selectedIcon = selectedIconId?.let { ImageCacher.getImageData(it) }) {
      is HTMLCanvasElement -> selectedIcon.toDataURL()
      is HTMLImageElement -> selectedIcon.src
      else -> "pj.png"
    }

    fun getFavIconLink() = document.querySelector("link[rel*='icon']") ?: document.createElement("link")

    val link = (getFavIconLink() as HTMLLinkElement).apply {
      type = "image/x-icon"
      rel = "shortcut icon"
      href = selectedIconUrl
    }
    document.head!!.appendChild(link)
  }

  @OptIn(ExperimentalStdlibApi::class)
  fun draw(windowId: Int, commands: List<ServerWindowEvent>) {
    if (windowId in excludedWindowIds) {
      return
    }

    synchronized(windowManager) {
      val window = windowManager[windowId]

      if (window == null) {
        logger.error { "Skipping nonexistent window: $windowId" }
        return
      }

      val newEvents = commands.shrinkByPaintEvents()

      if (newEvents.isNotEmpty()) {
        window.drawEvents.addAll(newEvents)
        window.drawBufferedEvents()
      }
    }
  }

  private fun removeAbsentWindows(presentedWindows: Iterable<WindowData>) {
    val presentedWindowIds = presentedWindows.map(WindowData::id).toSet()

    synchronized(windowManager) {
      windowManager.cleanup(presentedWindowIds)
    }
  }

  fun onResized() {
    synchronized(windowManager) {
      windowManager.forEach(Window::applyBounds)
    }
  }

  companion object {
    private val logger = Logger(WindowDataEventsProcessor::class.simpleName!!)
    private const val DEFAULT_TITLE = "Projector"
  }
}
