package com.example.epapernfc

import android.content.Intent
import android.nfc.NfcAdapter
import android.net.Uri
import android.provider.MediaStore
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.example.epapernfc.ui.theme.EpaperNFCTheme

class MainActivity : ComponentActivity() {
    private lateinit var editor: EditorViewModel
    private var adapter: NfcAdapter? = null
    private var nfcEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editor = ViewModelProvider(this)[EditorViewModel::class.java]
        adapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            EpaperNFCTheme {
                val state = editor.state
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::importPhoto)
                val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::importPhoto)
                val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == RESULT_OK) importPhoto(result.data?.data)
                }
                val saveBin = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    if (uri != null) editor.state.image?.let { editor.export(uri, false, it) }
                }
                val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
                    if (uri != null) editor.state.image?.let { editor.export(uri, true, it) }
                }
                EditorScreen(
                    state = state, hasNfc = adapter != null, nfcEnabled = nfcEnabled,
                    onImport = {
                        runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .onFailure { editor.report("无法打开系统相册，请尝试其他导入方式") }
                    },
                    onImportGallery = {
                        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
                        runCatching { galleryPicker.launch(Intent.createChooser(intent, "选择相册应用")) }
                            .onFailure { editor.report("本机没有支持选图的相册应用，请使用系统相册或从文件选择") }
                    },
                    onImportFile = { picker.launch(arrayOf("image/*")) },
                    onDither = editor::dither,
                    onSettings = editor::update,
                    onSend = editor::arm, onCancel = editor::cancelTransfer,
                    onNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    onSaveBin = { saveBin.launch("epaper_${System.currentTimeMillis()}.bin") },
                    onSavePng = { savePng.launch("epaper_preview_${System.currentTimeMillis()}.png") }
                )
            }
        }
    }

    private fun importPhoto(uri: Uri?) {
        if (uri == null) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        editor.load(uri)
    }

    override fun onResume() {
        super.onResume()
        nfcEnabled = adapter?.isEnabled == true
        if (nfcEnabled) {
            runCatching {
                adapter?.enableReaderMode(this, { tag -> runOnUiThread { editor.onTag(tag) } },
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
            }.onFailure { editor.report("无法启用 NFC：${it.message}") }
        }
    }

    override fun onPause() {
        editor.cancelTransfer()
        adapter?.disableReaderMode(this)
        super.onPause()
    }
}
