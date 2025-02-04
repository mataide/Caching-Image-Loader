package org.rakishou.cachingimageloader.core

import org.rakishou.cachingimageloader.builders.TransformationBuilder
import org.rakishou.cachingimageloader.cache.CacheValue
import org.rakishou.cachingimageloader.cache.DiskCache
import org.rakishou.cachingimageloader.http.DefaultHttpClient
import org.rakishou.cachingimageloader.http.HttpClientFacade
import javafx.embed.swing.SwingFXUtils
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.rakishou.cachingimageloader.transformations.ImageTransformation
import org.rakishou.cachingimageloader.transformations.TransformationType
import java.awt.image.BufferedImage
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext


class CachingImageLoader(
  maxDiskCacheSize: Long = defaultDiskCacheSize,
  rootDirectory: File = File(System.getProperty("user.dir")),
  private val showDebugLog: Boolean = true,
  private val client: HttpClientFacade = DefaultHttpClient(rootDirectory, showDebugLog),
  private val dispatcher: CoroutineDispatcher = newFixedThreadPoolContext(2, "caching-image-loader")
) : CoroutineScope {
  private val job = Job()

  private var diskCache: DiskCache
  private var imageCacheDir: File

  override val coroutineContext: CoroutineContext
    get() = job

  init {
    imageCacheDir = File(rootDirectory, "\\image-org.rakishou.cachingimageloader.cache")
    if (!imageCacheDir.exists()) {
      if (!imageCacheDir.mkdirs()) {
        throw IllegalStateException("Could not create image org.rakishou.cachingimageloader.cache directory: ${imageCacheDir.absolutePath}")
      }
    }

    diskCache = runBlocking {
      return@runBlocking DiskCache(maxDiskCacheSize, imageCacheDir, showDebugLog)
        .apply { init() }
    }
  }

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println("thread: ${Thread.currentThread().name}, $msg")
    }
  }

  fun newRequest(): RequestBuilder {
    return RequestBuilder()
  }

  private fun runRequest(
    loaderRequest: LoaderRequest,
    saveStrategy: SaveStrategy,
    url: String?,
    transformations: MutableList<ImageTransformation>
  ) {
    if (url == null) {
      throw RuntimeException("Url is not set!")
    }

    launch(dispatcher) {
      try {
        val fromCache = diskCache.get(url)
        val transformedBufferedImage = if (fromCache == null) {
          debugPrint("image ($url) does not exist in the org.rakishou.cachingimageloader.cache")

          val imageFile = downloadImage(url)

          try {
            if (imageFile == null) {
              onError(loaderRequest)
              return@launch
            }

            val cacheValue = CacheValue(imageFile, emptyArray())
            val (transformedImage, appliedTransformations) = applyTransformations(transformations, cacheValue)
            storeImage(saveStrategy, url, cacheValue, transformedImage, appliedTransformations)

            transformedImage
          } finally {
            imageFile?.let { imgFile ->
              Files.deleteIfExists(imgFile.toPath())
            }
          }
        } else {
          debugPrint("image ($url) already exists in the org.rakishou.cachingimageloader.cache")

          val (transformedImage, _) = applyTransformations(transformations, fromCache)
          transformedImage
        }

        val image = SwingFXUtils.toFXImage(transformedBufferedImage, null)
        onSuccess(loaderRequest, image)
      } catch (error: Throwable) {
        error.printStackTrace()
        onError(loaderRequest)
      }
    }
  }

  private suspend fun storeImage(
    saveStrategy: SaveStrategy,
    url: String,
    cacheValue: CacheValue,
    transformedImage: BufferedImage,
    appliedTransformations: List<TransformationType>
  ) {
    if (diskCache.contains(url)) {
      return
    }

    when (saveStrategy) {
      SaveStrategy.SaveOriginalImage -> diskCache.store(url, cacheValue)
      SaveStrategy.SaveTransformedImage -> {
        val result = ImageIO.write(transformedImage, ImageType.Png.value, cacheValue.file)
        if (!result) {
          throw RuntimeException("Could not save image to disk!")
        }

        diskCache.store(url, CacheValue(cacheValue.file, appliedTransformations.toTypedArray()))
      }
    }
  }

  private fun onError(loaderRequest: LoaderRequest) {
    debugPrint("onError")

    when (loaderRequest) {
      is LoaderRequest.DownloadAsyncRequest -> {
        loaderRequest.future.complete(null)
      }
      is LoaderRequest.DownloadAndShowRequest -> {
        setImageError(loaderRequest.imageView)
      }
    }
  }

  private fun onSuccess(loaderRequest: LoaderRequest, image: Image?) {
    debugPrint("onSuccess")

    when (loaderRequest) {
      is LoaderRequest.DownloadAsyncRequest -> {
        loaderRequest.future.complete(image)
      }
      is LoaderRequest.DownloadAndShowRequest -> {
        setImage(loaderRequest.imageView, image)
      }
    }
  }

  private fun applyTransformations(
    transformations: MutableList<ImageTransformation>,
    cacheValue: CacheValue
  ): Pair<BufferedImage, List<TransformationType>> {
    val bufferedImage = ImageIO.read(cacheValue.file)

    var outImage: BufferedImage? = null
    val appliedTransformations = mutableListOf<TransformationType>()

    for (transformation in transformations) {
      val inImage = outImage ?: bufferedImage

      //do not apply a transformation if it has already been applied
      outImage = if (transformation.type in cacheValue.appliedTransformations) {
        debugPrint("transformation (${transformation.type.name}) has already been applied")
        inImage
      } else {
        debugPrint("Applying transformation (${transformation.type.name})")
        transformation.transform(inImage)
      }

      appliedTransformations += transformation.type
    }

    val resultImage = outImage ?: bufferedImage
    return resultImage to appliedTransformations
  }

  fun shutdown() {
    job.cancel()
    client.close()
  }

  //for tests
  fun shutdownAndClearEverything() {
    shutdown()
    clearCache()
  }

  //for tests
  fun clearCache() {
    runBlocking {
      diskCache.clear()
    }
  }

  private suspend fun downloadImage(imageUrl: String): File? {
    try {
      val response = client.fetchImage(imageUrl).await()
      if (response == null) {
        debugPrint("Couldn't retrieve response")
        return null
      }

      val contentType = response.contentType
      val contentFile = response.contentFile

      if (contentType == null) {
        debugPrint("contentType is null")
        return null
      }

      if (contentFile == null) {
        debugPrint("content is null")
        return null
      }

      val imageType = when (response.contentType) {
        "image/png" -> ImageType.Png
        "image/jpeg",
        "image/jpg" -> ImageType.Jpg
        else -> null
      }

      if (imageType == null) {
        debugPrint("Content-type is not supported")
        return null
      }

      return contentFile
    } catch (error: Throwable) {
      error.printStackTrace()
      return null
    }
  }

  private fun setImageError(imageView: WeakReference<ImageView>) {
    //TODO
  }

  private fun setImage(imageView: WeakReference<ImageView>, image: Image?) {
    imageView.get()?.let { iv ->
      image?.let { img ->
        iv.image = img
      }
    }
  }

  private fun setImage(imagePane: Pane, image: Image?) {
    val imageView = ImageView()
    image?.let { img ->
      imageView.image = img
    }
    imageView.fitHeightProperty().bind(imagePane.prefHeightProperty());
    imageView.fitWidthProperty().bind(imagePane.prefWidthProperty());

    imagePane.children?.add(imageView)
  }

  inner class RequestBuilder {
    private var url: String? = null
    private var saveStrategy = SaveStrategy.SaveOriginalImage
    private val transformers = mutableListOf<ImageTransformation>()

    fun load(url: String): TransformationsOptions {
      this.url = url
      return TransformationsOptions()
    }

    inner class TransformationsOptions {
      fun transformations(builder: TransformationBuilder): SaveOptions {
        transformers.addAll(builder.getTransformers())
        return SaveOptions()
      }
    }

    inner class SaveOptions {
      fun saveStrategy(_saveStrategy: SaveStrategy): TerminalOptions {
        saveStrategy = _saveStrategy
        return TerminalOptions()
      }
    }

    inner class TerminalOptions {
      fun getAsync(): CompletableFuture<Image?> {
        val future = CompletableFuture<Image?>()
        runRequest(LoaderRequest.DownloadAsyncRequest(future), saveStrategy, url, transformers)
        return future
      }

      fun into(imageView: ImageView) {
        runRequest(LoaderRequest.DownloadAndShowRequest(WeakReference(imageView)), saveStrategy, url, transformers)
      }
    }
  }

  companion object {
    const val defaultDiskCacheSize = 1024 * 1024 * 96L
  }
}