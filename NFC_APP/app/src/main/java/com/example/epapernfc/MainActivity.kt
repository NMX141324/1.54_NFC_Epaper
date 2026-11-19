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
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    importPhoto(uri)
                }
                val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == RESULT_OK) importPhoto(result.data?.data)
                }
                EditorScreen(
                    state = editor.state, hasNfc = adapter != null, nfcEnabled = nfcEnabled,
                    onImport = {
                        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                            type = "image/*"
                        }
                        runCatching {
                            if (intent.resolveActivity(packageManager) != null) {
                                gallery.launch(Intent.createChooser(intent, "选择照片"))
                            } else picker.launch(arrayOf("image/*"))
                        }
                            .onFailure {
                                runCatching { picker.launch(arrayOf("image/*")) }
                                    .onFailure { editor.report("无法打开相册，请检查相册应用") }
                            }
                    },
                    onDither = editor::dither,
                    onSettings = editor::update,
                    onGestureStart = editor::beginGesture,
                    onSend = editor::arm,
                    onCancel = editor::cancelTransfer,
                    onNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
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
            }.onFailure { editor.report("无法启用 NFC") }
        }
    }

    override fun onPause() {
        editor.cancelTransfer()
        adapter?.disableReaderMode(this)
        super.onPause()
    }
}
