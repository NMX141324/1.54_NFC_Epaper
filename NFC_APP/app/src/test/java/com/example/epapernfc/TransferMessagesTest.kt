package com.example.epapernfc

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TransferMessagesTest {
    @Test fun longRfDiagnosticBecomesOneShortReason() {
        val error = IOException("ST25DV 错误 0F：AA / flags=02 / TX=240\n阶段：发送第4段\nMCU 已确认6144字节")
        assertEquals("设备忙，请贴紧后重试", transferErrorSummary(error, false))
    }
    @Test fun acceptedImageStillNeedsActualRefreshCompletion() {
        assertEquals("刷新中断，请保持供电后重新发送", transferErrorSummary(IOException("Tag was lost"), true))
        assertEquals("等待刷新结果超时", transferErrorSummary(IOException("330 秒未收到刷屏结果"), true))
        assertEquals("屏幕刷新异常，请检查供电后重试", transferErrorSummary(IOException("屏幕刷新失败：-10"), true))
    }
    @Test fun compatibilityAndCrcFailuresStayActionable() {
        assertEquals("请更新配套固件", transferErrorSummary(IOException("请更新配套固件"), false))
        assertEquals("数据校验失败，请重新发送", transferErrorSummary(IOException("图像 CRC 错误"), false))
        assertEquals("连接超时，请重新靠近", transferErrorSummary(IOException("等待 MCU 超时"), false))
    }
}
