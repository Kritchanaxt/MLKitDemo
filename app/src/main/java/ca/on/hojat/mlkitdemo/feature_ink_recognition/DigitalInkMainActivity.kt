package ca.on.hojat.mlkitdemo.feature_ink_recognition

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import ca.on.hojat.mlkitdemo.databinding.ActivityDigitalInkMainKotlinBinding
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedSet
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import java.util.Locale

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import com.google.mlkit.vision.digitalink.Ink
import java.io.BufferedReader
import java.io.InputStreamReader

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import android.graphics.ImageDecoder

import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.core.CvException

import com.caverock.androidsvg.SVG
import android.graphics.Canvas

/** Main activity which creates a StrokeManager and connects it to the DrawingView. */
class DigitalInkMainActivity : AppCompatActivity(), StrokeManager.DownloadedModelsChangedListener {

    private lateinit var binding: ActivityDigitalInkMainKotlinBinding

    @JvmField
    @VisibleForTesting
    val strokeManager = StrokeManager()
    private lateinit var languageAdapter: ArrayAdapter<ModelLanguageContainer>


    private val importInkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    importInkFromFile(uri)
                }
            }
        }

    // Launcher for selecting images
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    try {
                        // Convert Uri to Bitmap
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        val decodedBitmap = ImageDecoder.decodeBitmap(source)

                        val bitmap = decodedBitmap.copy(Bitmap.Config.ARGB_8888, true)

                        // Start the conversion process with an editable bitmap.
                        val ink = convertBitmapToInk(bitmap)
                        strokeManager.importInk(ink)
                        Toast.makeText(this, "Image converted!", Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting image: ${e.message}")
                        Toast.makeText(this, "Failed to convert image.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    Log.i(TAG, "OpenCV loaded successfully")
                    // When loading is complete, enable the Convert Image button.
                    binding.convertFromImageButton.isEnabled = true
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    private val importSvgLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    importSvgFromFile(uri)
                }
            }
        }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDigitalInkMainKotlinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.drawingView.setStrokeManager(strokeManager)
        binding.statusTextView.setStrokeManager(strokeManager)
        strokeManager.setStatusChangedListener(binding.statusTextView)
        strokeManager.setContentChangedListener(binding.drawingView)
        strokeManager.setDownloadedModelsChangedListener(this)
        strokeManager.setClearCurrentInkAfterRecognition(true)
        strokeManager.setTriggerRecognitionAfterInput(false)
        languageAdapter = populateLanguageAdapter()
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languagesSpinner.adapter = languageAdapter
        strokeManager.refreshDownloadedModelsStatus()

        binding.languagesSpinner.onItemSelectedListener =
            object : OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View,
                    position: Int,
                    id: Long
                ) {
                    val languageCode =
                        (parent.adapter.getItem(position) as ModelLanguageContainer).languageTag
                            ?: return
                    Log.i(TAG, "Selected language: $languageCode")
                    strokeManager.setActiveModel(languageCode)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    Log.i(TAG, "No language selected")
                }
            }
        strokeManager.reset()

        // register some other listeners
        binding.downloadButton.setOnClickListener {
            strokeManager.download()
        }
        binding.recognizeButton.setOnClickListener {
            strokeManager.recognize()
        }
        binding.clearButton.setOnClickListener {
            strokeManager.reset()
            binding.drawingView.clear()
        }
        binding.deleteButton.setOnClickListener {
            strokeManager.deleteActiveModel()
        }

        binding.exportButton.setOnClickListener {
            exportInkToFile()
        }

        binding.importButton.setOnClickListener {
            openFilePicker()
        }

        binding.convertFromImageButton.setOnClickListener {
            openImagePicker()
        }
        // Close the button first, wait for OpenCV to finish loading.
        binding.convertFromImageButton.isEnabled = false

        binding.importSvgButton.setOnClickListener {
            openSvgFilePicker()
        }

    }

    private class ModelLanguageContainer
    private constructor(private val label: String, val languageTag: String?) :
        Comparable<ModelLanguageContainer> {

        var downloaded: Boolean = false

        override fun toString(): String {
            return when {
                languageTag == null -> label
                downloaded -> "   [D] $label"
                else -> "   $label"
            }
        }

        override fun compareTo(other: ModelLanguageContainer): Int {
            return label.compareTo(other.label)
        }

        companion object {
            /** Populates and returns a real model identifier, with label and language tag. */
            fun createModelContainer(label: String, languageTag: String?): ModelLanguageContainer {
                // Offset the actual language labels for better readability
                return ModelLanguageContainer(label, languageTag)
            }

            /** Populates and returns a label only, without a language tag. */
            fun createLabelOnly(label: String): ModelLanguageContainer {
                return ModelLanguageContainer(label, null)
            }
        }
    }

    private fun populateLanguageAdapter(): ArrayAdapter<ModelLanguageContainer> {
        val languageAdapter =
            ArrayAdapter<ModelLanguageContainer>(this, android.R.layout.simple_spinner_item)
        languageAdapter.add(ModelLanguageContainer.createLabelOnly("Select language"))
        languageAdapter.add(ModelLanguageContainer.createLabelOnly("Non-text Models"))

        // Manually add non-text models first
        for (languageTag in NON_TEXT_MODELS.keys) {
            languageAdapter.add(
                ModelLanguageContainer.createModelContainer(
                    NON_TEXT_MODELS[languageTag]!!,
                    languageTag
                )
            )
        }
        languageAdapter.add(ModelLanguageContainer.createLabelOnly("Text Models"))
        val textModels = ImmutableSortedSet.naturalOrder<ModelLanguageContainer>()
        for (modelIdentifier in DigitalInkRecognitionModelIdentifier.allModelIdentifiers()) {
            if (NON_TEXT_MODELS.containsKey(modelIdentifier.languageTag)) {
                continue
            }
            if (modelIdentifier.languageTag.endsWith(GESTURE_EXTENSION)) {
                continue
            }
            textModels.add(buildModelContainer(modelIdentifier, "Script"))
        }
        languageAdapter.addAll(textModels.build())
        languageAdapter.add(ModelLanguageContainer.createLabelOnly("Gesture Models"))
        val gestureModels = ImmutableSortedSet.naturalOrder<ModelLanguageContainer>()
        for (modelIdentifier in DigitalInkRecognitionModelIdentifier.allModelIdentifiers()) {
            if (!modelIdentifier.languageTag.endsWith(GESTURE_EXTENSION)) {
                continue
            }
            gestureModels.add(buildModelContainer(modelIdentifier, "Script gesture classifier"))
        }
        languageAdapter.addAll(gestureModels.build())
        return languageAdapter
    }

    private fun buildModelContainer(
        modelIdentifier: DigitalInkRecognitionModelIdentifier,
        labelSuffix: String
    ): ModelLanguageContainer {
        val label = StringBuilder()
        label.append(Locale(modelIdentifier.languageSubtag).displayName)
        if (modelIdentifier.regionSubtag != null) {
            label.append(" (").append(modelIdentifier.regionSubtag).append(")")
        }
        if (modelIdentifier.scriptSubtag != null) {
            label.append(", ").append(modelIdentifier.scriptSubtag).append(" ").append(labelSuffix)
        }
        return ModelLanguageContainer.createModelContainer(
            label.toString(),
            modelIdentifier.languageTag
        )
    }

    override fun onDownloadedModelsChanged(downloadedLanguageTags: Set<String>) {
        for (i in 0 until languageAdapter.count) {
            val container = languageAdapter.getItem(i)!!
            container.downloaded = downloadedLanguageTags.contains(container.languageTag)
        }
        languageAdapter.notifyDataSetChanged()
    }

    // Function for Export
    private fun exportInkToFile() {
        val ink = binding.drawingView.getAllStrokesAsInk()
        if (ink.strokes.isEmpty()) {
            Toast.makeText(this, "Nothing to export.", Toast.LENGTH_SHORT).show()
            return
        }

        // Convert Ink object to created data class.
        val inkData = InkData(
            strokes = ink.strokes.map { stroke ->
                StrokeData(
                    points = stroke.points.map { point ->
                        PointData(point.x, point.y)
                    }
                )
            }
        )

        val gson = Gson()
        val jsonString = gson.toJson(inkData)
        saveJsonToFile(jsonString)
    }

    // Function to save a JSON file using MediaStore.
    private fun saveJsonToFile(jsonString: String) {
        val fileName = "InkDrawing_${System.currentTimeMillis()}.json"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MyInkDrawings")
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

        uri?.let {
            try {
                resolver.openOutputStream(it).use { outputStream ->
                    outputStream?.write(jsonString.toByteArray())
                    Toast.makeText(this, "Exported to Downloads/MyInkDrawings", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file: ${e.message}")
                Toast.makeText(this, "Failed to export file.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Function to open the file selection page.
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importInkLauncher.launch(intent)
    }

    // New function to open image selection page
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    // Import function
    private fun importInkFromFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()

            val gson = Gson()
            val inkData = gson.fromJson(jsonString, InkData::class.java)

            // Convert InkData back to ML Kit's Ink object.
            val inkBuilder = Ink.builder()
            inkData.strokes.forEach { strokeData ->
                val strokeBuilder = Ink.Stroke.builder()
                strokeData.points.forEach { pointData ->
                    strokeBuilder.addPoint(Ink.Point.create(pointData.x, pointData.y, 0))
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }

            strokeManager.importInk(inkBuilder.build())

        } catch (e: Exception) {
            Log.e(TAG, "Error importing file: ${e.message}")
            Toast.makeText(this, "Failed to import file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun convertBitmapToInk(bitmap: Bitmap): Ink {
        val originalMat = Mat()
        Utils.bitmapToMat(bitmap, originalMat)

        val grayMat = Mat()
        Imgproc.cvtColor(originalMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val binaryMat = Mat()
        Imgproc.adaptiveThreshold(
            grayMat, // source image
            binaryMat, // destination image
            255.0, // max value
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            17, // block size
            10.0 // constant C
        )

        val erodedMat = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.erode(binaryMat, erodedMat, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            erodedMat,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        val inkBuilder = Ink.builder()
        contours.forEach { contour ->
            if (Imgproc.contourArea(contour) > 10) {
                val strokeBuilder = Ink.Stroke.builder()
                contour.toList().forEach { point ->
                    strokeBuilder.addPoint(Ink.Point.create(point.x.toFloat(), point.y.toFloat(), 0))
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }
        }

        originalMat.release()
        grayMat.release()
        binaryMat.release()
        erodedMat.release()
        kernel.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return inkBuilder.build()
    }


    private fun openSvgFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Specify MIME type for SVG file.
            type = "image/svg+xml"
        }
        importSvgLauncher.launch(intent)
    }

    private fun importSvgFromFile(uri: Uri) {
        try {
            // 1. Read SVG file from InputStream
            val inputStream = contentResolver.openInputStream(uri)
            val svg = SVG.getFromInputStream(inputStream)

            // 2. Prepare the Canvas to draw SVG on Bitmap.
            val width = svg.documentWidth.takeIf { it > 0 }?.toInt() ?: 1024
            val height = svg.documentHeight.takeIf { it > 0 }?.toInt() ?: 1024
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 3. Render (draw) SVG onto our Canvas.
            svg.renderToCanvas(canvas)

            // 4. Reuse the obtained Bitmap from SVG with our original function
            val ink = convertBitmapToInk(bitmap)

            // 5. Take the resulting Ink and display it.
            strokeManager.importInk(ink)
            Toast.makeText(this, "SVG Imported successfully!", Toast.LENGTH_SHORT).show()

            inputStream?.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error importing SVG file: ${e.message}")
            Toast.makeText(this, "Failed to import SVG file.", Toast.LENGTH_SHORT).show()
        }
    }

    public override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback)
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        }
    }

    companion object {
        private const val TAG = "MLKDI.Activity"
        private val NON_TEXT_MODELS =
            ImmutableMap.of(
                "zxx-Zsym-x-autodraw",
                "Autodraw",
                "zxx-Zsye-x-emoji",
                "Emoji",
                "zxx-Zsym-x-shapes",
                "Shapes"
            )
        private const val GESTURE_EXTENSION = "-x-gesture"
    }
}
