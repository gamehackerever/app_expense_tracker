package com.expensetracker.offline.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.math.max

object ImageUtil {
    private const val TAG = "BillUpload"

    fun loadScaledBitmap(context: Context, pathOrUri: String, maxDimension: Int = 1024): Bitmap? {
        Log.d(TAG, "loadScaledBitmap called with: $pathOrUri")
        return try {
            val file = File(pathOrUri)
            val isFile = file.exists()
            val uri = if (isFile) null else Uri.parse(pathOrUri)
            Log.d(TAG, "Is file? $isFile. URI: $uri")

            // 1. Decode bounds first
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            if (isFile) {
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
            }

            Log.d(TAG, "Bounds decoded. outWidth: ${options.outWidth}, outHeight: ${options.outHeight}")
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "Invalid bounds. Returning null.")
                return null
            }

            // 2. Calculate inSampleSize
            var inSampleSize = 1
            val largestDim = max(options.outWidth, options.outHeight)
            while (largestDim / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }
            Log.d(TAG, "Calculated inSampleSize: $inSampleSize")

            // 3. Decode actual bitmap
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize

            val bitmap = if (isFile) {
                BitmapFactory.decodeFile(file.absolutePath, options)
            } else {
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                }
            } ?: return null

            // 4. Read EXIF Orientation and build a rotation Matrix
            val exif = if (isFile) {
                ExifInterface(file.absolutePath)
            } else {
                uri?.let {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        ExifInterface(stream)
                    }
                }
            }

            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    matrix.preScale(1.0f, -1.0f)
                    matrix.postRotate(180f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.preScale(-1.0f, 1.0f)
                    matrix.postRotate(90f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.preScale(-1.0f, 1.0f)
                    matrix.postRotate(270f)
                }
            }

            // 5. Apply the rotation and free the memory of the original unrotated image
            if (!matrix.isIdentity) {
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                rotatedBitmap
            } else {
                bitmap
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception in loadScaledBitmap", e)
            null
        }
    }
}