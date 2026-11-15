package com.example.epapernfc

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.epapernfc.ui.theme.EpaperNFCTheme

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun EditorPhonePreview() {
    EpaperNFCTheme {
        EditorScreen(EditorState(), hasNfc = true, nfcEnabled = true,
            onImport = {}, onSettings = {}, onDither = {}, onImportGallery = {}, onImportFile = {},
            onSend = {}, onCancel = {}, onNfcSettings = {},
            onSaveBin = {}, onSavePng = {})
    }
}
