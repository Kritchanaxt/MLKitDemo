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

    // ฟังก์ชันสำหรับ Export
    private fun exportInkToFile() {
        val ink = binding.drawingView.getAllStrokesAsInk()
        if (ink.strokes.isEmpty()) {
            Toast.makeText(this, "Nothing to export.", Toast.LENGTH_SHORT).show()
            return
        }

        // แปลง Ink object เป็น data class ที่สร้างไว้
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

    // ฟังก์ชันสำหรับบันทึกไฟล์ JSON โดยใช้ MediaStore
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

    // ฟังก์ชันสำหรับเปิดหน้าเลือกไฟล์
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importInkLauncher.launch(intent)
    }

    // ฟังก์ชันสำหรับ Import
    private fun importInkFromFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()

            val gson = Gson()
            val inkData = gson.fromJson(jsonString, InkData::class.java)

            // แปลง InkData กลับเป็น Ink object ของ ML Kit
            val inkBuilder = Ink.builder()
            inkData.strokes.forEach { strokeData ->
                val strokeBuilder = Ink.Stroke.builder()
                strokeData.points.forEach { pointData ->
                    strokeBuilder.addPoint(Ink.Point.create(pointData.x, pointData.y, 0))
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }

            // ส่ง Ink ที่ได้ไปให้ StrokeManager
            strokeManager.importInk(inkBuilder.build())

        } catch (e: Exception) {
            Log.e(TAG, "Error importing file: ${e.message}")
            Toast.makeText(this, "Failed to import file.", Toast.LENGTH_SHORT).show()
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
