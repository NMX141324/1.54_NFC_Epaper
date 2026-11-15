package com.example.epapernfc

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.example.epapernfc.ui.theme.EpaperNFCTheme

@Preview(showBackground = true, widthDp = 393, heightDp = 1100)
@Composable
private fun ManualDitherPreview() {
    val adjusted = remember {
        Bitmap.createBitmap(IntArray(57600) { i -> EpaperCodec.colors[(i % 240) / 40] },
            240, 240, Bitmap.Config.ARGB_8888)
    }
    EpaperNFCTheme {
        EditorScreen(EditorState(adjusted = adjusted, hasSource = true,
            status = "调色预览已更新，请点击「开始抖动」"), hasNfc = true, nfcEnabled = true,
            onImport = {}, onSettings = {}, onDither = {}, onImportGallery = {}, onImportFile = {},
            onSend = {}, onCancel = {}, onNfcSettings = {}, onSaveBin = {}, onSavePng = {})
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TransferDialogPreview() {
    EpaperNFCTheme {
        TransferProgressDialog(EditorState(sending = true, showTransferDialog = true,
            progress = 12288f / 28833f, status = "正在发送图片，请保持贴紧天线"), onClose = {})
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TransferFailurePreview() {
    EpaperNFCTheme {
        TransferProgressDialog(EditorState(showTransferDialog = true,
            error = "传输失败：手机 NFC 未收到标签响应（Tag was lost）\n阶段：等待 MCU 握手回执\n最后命令：AD / flags=02 / TX=4 字节\nMB_CTRL=05；已确认 0/28833 字节；MCU 握手=尚未成功\n手机：OnePlus / Android\n移开手机后重新点击发送，将从整图开始。"), onClose = {})
    }
}
