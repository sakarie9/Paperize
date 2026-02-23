package com.anthonyla.paperize.feature.wallpaper.presentation.wallpaper_screen.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.core.net.toUri
import androidx.core.content.ContextCompat.startActivity
import com.anthonyla.paperize.core.ScalingConstants
import com.anthonyla.paperize.core.VignetteBitmapTransformation
import com.anthonyla.paperize.core.decompress
import com.anthonyla.paperize.core.isValidUri
import com.anthonyla.paperize.R
import com.bumptech.glide.request.RequestOptions
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.glide.GlideImage

/**
 * A composable that displays a wallpaper for preview
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PreviewItem(
    wallpaperUri: String,
    darken: Boolean,
    darkenPercentage: Int,
    blur: Boolean,
    blurPercentage: Int,
    scaling: ScalingConstants,
    vignette: Boolean,
    vignettePercentage: Int,
    grayscale: Boolean,
    grayscalePercentage: Int
) {
    val context = LocalContext.current
    val showUri by remember { mutableStateOf(isValidUri(context, wallpaperUri)) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val openImageExternally: (String) -> Unit = { uriString ->
        val originalUri = uriString.decompress("content://com.android.externalstorage.documents/").toUri()
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(originalUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(viewIntent, context.getString(R.string.open_with))
        try {
            startActivity(context, chooser, null)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.no_app_available_to_open_image), Toast.LENGTH_SHORT).show()
        }
    }

    if (showUri) {
        val uri = wallpaperUri.decompress("content://com.android.externalstorage.documents/").toUri()
        GlideImage(
            imageModel = { uri },
            imageOptions = ImageOptions(
                contentScale = when (scaling) {
                    ScalingConstants.FILL -> ContentScale.FillHeight
                    ScalingConstants.FIT -> ContentScale.FillWidth
                    ScalingConstants.STRETCH -> ContentScale.FillBounds
                    ScalingConstants.NONE -> ContentScale.Crop
                },
                requestSize = IntSize(300, 300),
                alignment = if (scaling == ScalingConstants.NONE) Alignment.CenterStart else Alignment.Center,
                colorFilter = if (darken && darkenPercentage < 100) {
                    ColorFilter.tint(
                        Color.Black.copy(alpha = (100 - darkenPercentage).toFloat().div(100f)),
                        BlendMode.Darken
                    )
                } else { null },
                tag = vignette.toString() + vignettePercentage.toString() + grayscale.toString() + grayscalePercentage.toString(),
            ),
            requestOptions = {
                when {
                    grayscale && grayscalePercentage > 0 && vignette && vignettePercentage > 0 -> {
                        RequestOptions().transform(
                            VignetteBitmapTransformation(vignettePercentage),
                            GrayscaleBitmapTransformation(grayscalePercentage)
                        )
                    }
                    grayscale && grayscalePercentage > 0 -> {
                        RequestOptions().transform(GrayscaleBitmapTransformation(grayscalePercentage))
                    }
                    vignette && vignettePercentage > 0 -> {
                        RequestOptions().transform(VignetteBitmapTransformation(vignettePercentage))
                    }
                    else -> RequestOptions()
                }
            },
            modifier = Modifier
                .size(screenWidth * 0.35f, screenHeight * 0.35f)
                .clip(RoundedCornerShape(16.dp))
                .border(3.dp, Color.Black, RoundedCornerShape(16.dp))
                .background(Color.Black)
                .clickable {
                    openImageExternally(wallpaperUri)
                }
                .blur(
                    if (blur && blurPercentage > 0) {
                        blurPercentage
                            .toFloat()
                            .div(100f) * 1.5.dp
                    } else {
                        0.dp
                    }
                )
        )
    }
}