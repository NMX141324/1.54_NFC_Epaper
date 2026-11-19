package com.example.epapernfc

/** Keep RF diagnostics in Logcat, not in the phone's progress dialog. */
fun transferErrorSummary(error: Throwable, imageAccepted: Boolean): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("取消") -> "已取消"
        message.contains("配套固件") || message.contains("r7") -> "请更新配套固件"
        message.contains("不属于本张") || message.contains("回执不匹配") -> "刷新回执不匹配，请重试"
        message.contains("屏幕刷新失败") -> "屏幕刷新异常，请检查供电后重试"
        imageAccepted && (message.contains("超时") || message.contains("330")) -> "等待刷新结果超时"
        imageAccepted -> "刷新中断，请保持供电后重新发送"
        message.contains("0F", ignoreCase = true) -> "设备忙，请贴紧后重试"
        message.contains("超时") -> "连接超时，请重新靠近"
        message.contains("MB_MODE") || message.contains("Mailbox 已关闭") -> "设备未就绪，请重新上电"
        message.contains("校验") || message.contains("CRC") -> "数据校验失败，请重新发送"
        message.contains("ST25DV04K-IE") -> "请靠近配套墨水屏"
        message.contains("Tag was lost", ignoreCase = true) || message.contains("标签响应") ||
            message.contains("连接") -> "NFC 连接中断，请贴紧后重试"
        else -> "发送失败，请贴紧后重试"
    }
}
