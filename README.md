# Caching-Image-Loader
Image loader for JavaFX's ImageView.

Samples
---

First of all you need to create `CachingImageLoader`. 
Usually it should be stored in some singleton class.
Then you can download an image and render it in an `ImageView` like this:

```
imageLoader.newRequest()
  .load("image_url.com/123.png")
  .into(imageView)
```

This will also store downloaded image onto the disk. 
By default it will create a subdirectory named `image-cache` in the folder where the binary file is located:
`File(System.getProperty("user.dir"), "\\image-cache"`)


Now lets apply some transformations:
- CenterCrop: will scale the image down so that one of the image's dimensions fits target dimensions and then crop out the center of the image
- CircleCrop: will draw the image inside a circle 

Now also store it in the cache with already applied transformations so we don't have to apply them every time we load this image from the cache:

```
imageLoader.newRequest()
  .load("image_url.com/123.png")
  .transformers(
    TransformerBuilder()
      .fitCenter(imageView)
      .circleCrop(
        CircleCropParametersBuilder()
          .backgroundColor(Color.RED)
          .stroke(10f, Color.GREEN)
      )
  )
  .saveStrategy(SaveStrategy.SaveTransformedImage)
  .into(imageView)
```

There is also an option to retrieve a CompletableDeferred holding the Image object with help of the `getAsync` method:

```
imageLoader.newRequest()
  .load(getImage())
  .getAsync()
```
