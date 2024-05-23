package com.document.scanner.flutter.document_scanner_flutter

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
//import com.scanlibrary.ScanActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import android.R.attr.data
import android.app.ProgressDialog
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import androidx.exifinterface.media.ExifInterface
import com.scanlibrary.ProgressDialogFragment
import com.scanlibrary.ScanActivity
import com.scanlibrary.ScanConstants
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import kotlin.collections.HashMap


/** DocumentScannerFlutterPlugin */
class DocumentScannerFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var call: MethodCall

    /// For activity binding
    private var activityPluginBinding: ActivityPluginBinding? = null
    private var result: Result? = null

    /// For scanner library
    companion object {
        val SCAN_REQUEST_CODE: Int = 101
    }

    lateinit var mCurrentPhotoPath: String
    private val scannedBitmaps: ArrayList<Uri> = ArrayList()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "document_scanner_flutter")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        this.call = call
        this.result = result

        when (call.method) {
            "camera" -> {
                camera()
            }

            "gallery" -> {
                gallery()
            }

            "retrieveLostData" -> {
                activityPluginBinding?.activity?.apply {
                    val sharedPref = this.getSharedPreferences("AppData", Context.MODE_PRIVATE)
                    val imageSaved = sharedPref.getString("imagePathDocumentScanner", null)

                    // * Remove
                    with(sharedPref.edit()) {
                        remove("imagePathDocumentScanner")
                        apply()
                    }

                    result.success(imageSaved)
                    return
                }

                result.success(null)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityPluginBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityPluginBinding?.removeActivityResultListener(this)
        activityPluginBinding = null
    }

    private fun composeIntentArguments(intent: Intent) = mapOf(
        ScanConstants.SCAN_NEXT_TEXT to "ANDROID_NEXT_BUTTON_LABEL",
        ScanConstants.SCAN_SAVE_TEXT to "ANDROID_SAVE_BUTTON_LABEL",
        ScanConstants.SCAN_ROTATE_LEFT_TEXT to "ANDROID_ROTATE_LEFT_LABEL",
        ScanConstants.SCAN_ROTATE_RIGHT_TEXT to "ANDROID_ROTATE_RIGHT_LABEL",
        ScanConstants.SCAN_ORG_TEXT to "ANDROID_ORIGINAL_LABEL",
        ScanConstants.SCAN_BNW_TEXT to "ANDROID_BMW_LABEL",
        ScanConstants.SCAN_SCANNING_MESSAGE to "ANDROID_SCANNING_MESSAGE",
        ScanConstants.SCAN_LOADING_MESSAGE to "ANDROID_LOADING_MESSAGE",
        ScanConstants.SCAN_APPLYING_FILTER_MESSAGE to "ANDROID_APPLYING_FILTER_MESSAGE",
        ScanConstants.SCAN_CANT_CROP_ERROR_TITLE to "ANDROID_CANT_CROP_ERROR_TITLE",
        ScanConstants.SCAN_CANT_CROP_ERROR_MESSAGE to "ANDROID_CANT_CROP_ERROR_MESSAGE",
        ScanConstants.SCAN_OK_LABEL to "ANDROID_OK_LABEL",
    ).entries.filter { call.hasArgument(it.value) && call.argument<String>(it.value) != null }.forEach {
        intent.putExtra(it.key, call.argument<String>(it.value))
    }

    private fun camera() {
        activityPluginBinding?.activity?.apply {
            val intent = Intent(this, ScanActivity::class.java)
            intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, ScanConstants.OPEN_CAMERA)


            val hasInitialImage = call.hasArgument("INITIAL_IMAGE")
            if (hasInitialImage) {
                try {
                    GlobalScope.launch(Dispatchers.Main) {
                        val initialImage = call.argument<ByteArray>("INITIAL_IMAGE") ?: return@launch

                        val messageLoading =
                            call.argument<String>("ANDROID_INITIAL_IMAGE_LOADING_MESSAGE") ?: "Saving image..."

                        val progressDialog = ProgressDialog(activityPluginBinding!!.activity).apply {
                            setMessage(messageLoading)
                            setCancelable(false)
                            setCanceledOnTouchOutside(false)
                            show()
                        }

                        val rotationDegrees = withContext(Dispatchers.IO) {
                            getRotationDegreesFromByteArray(initialImage)
                        }

                        val uri = withContext(Dispatchers.IO) {
                            saveImageToFile(activityPluginBinding!!.activity, initialImage, rotationDegrees)
                        }

                        progressDialog.dismiss()

                        uri?.let {
                            intent.putExtra(ScanConstants.INITIAL_IMAGE, it.toString())
                            intent.putExtra(
                                ScanConstants.CAN_BACK_TO_INITIAL,
                                call.argument<Boolean?>("CAN_BACK_TO_INITIAL")
                            )
                        }

                        composeIntentArguments(intent)
                        startActivityForResult(intent, SCAN_REQUEST_CODE)
                    }

                    return
                } catch (e: Exception) {
                    Log.wtf("Document_Scanner", e)
                }
            }

            composeIntentArguments(intent)
            startActivityForResult(intent, SCAN_REQUEST_CODE)
        }
    }

    private fun gallery() {
        activityPluginBinding?.activity?.apply {
            val intent = Intent(this, ScanActivity::class.java)
            intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, ScanConstants.OPEN_MEDIA)
            composeIntentArguments(intent)
            startActivityForResult(intent, SCAN_REQUEST_CODE)
        }
    }

    fun getRealPathFromUri(context: Context, contentUri: Uri?): String? {
        var cursor: Cursor? = null
        return try {
            val proj = arrayOf(MediaStore.Images.Media.DATA)
            cursor = context.getContentResolver().query(contentUri!!, proj, null, null, null)
            val column_index: Int = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            cursor.getString(column_index)
        } finally {
            if (cursor != null) {
                cursor.close()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        // React to activity result and if request code == ResultActivity.REQUEST_CODE
        return when (resultCode) {
            Activity.RESULT_OK -> {
                if (requestCode == SCAN_REQUEST_CODE) {
                    activityPluginBinding?.activity?.apply {
                        val uri = data!!.extras!!.getParcelable<Uri>(ScanConstants.SCANNED_RESULT)
                        val realPath = getRealPathFromUri(activityPluginBinding!!.activity, uri)

                        val sharedPref = this.getSharedPreferences("AppData", Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putString("imagePathDocumentScanner", realPath)
                            apply()
                        }

                        if (result != null) {
                            result?.success(realPath)
                            result = null
                        }
                    }
                }

                true
            }

            Activity.RESULT_CANCELED -> {
                if (result != null) {
                    result?.success(null)
                    result = null
                }

                false
            }

            else -> {
                result = null
                false
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    private fun getRotationDegreesFromByteArray(byteArray: ByteArray): Float {
        ByteArrayInputStream(byteArray).use { inputStream ->
            val exifInterface = ExifInterface(inputStream)
            val orientation =
                exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                else -> 0f
            }
        }
    }

    private fun saveImageToFile(context: Context, byteArray: ByteArray, rotationDegrees: Float): Uri? {
        // ByteArray to Bitmap
        var bitmap: Bitmap? = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

        try {
            if (bitmap == null) return null

            val rotatedBitmap = if (rotationDegrees != 0f) {
                val rotated = rotateBitmap(bitmap, rotationDegrees)
                bitmap.recycle()
                bitmap = null
                rotated
            } else {
                bitmap
            }

            // Make a temporal file
            val file = File(context.externalCacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { output ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            }

            // Insert image in MediaStore & return Uri
            val contentUri: String? = MediaStore.Images.Media.insertImage(
                context.contentResolver,
                rotatedBitmap,
                "Title - ${System.currentTimeMillis()}",
                null
            )

            rotatedBitmap.recycle()
            return Uri.parse(contentUri)
        } catch (e: Exception) {
            bitmap?.recycle()
            e.printStackTrace()
        }
        return null
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(angle)
        }

        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
