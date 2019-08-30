package org.rakishou.cachingimageloader.core

import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

sealed class LoaderRequest {
  class DownloadAsyncRequest(val future: CompletableFuture<Image?>) : LoaderRequest()
  class DownloadAndShowRequest(val imageView: WeakReference<ImageView>) : LoaderRequest()
  class DownloadAndShowRequestToPane(val imagePane: WeakReference<Pane>) : LoaderRequest()
}