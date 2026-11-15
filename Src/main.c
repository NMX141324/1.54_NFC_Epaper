#include "app_debug.h"
#include "board.h"
#include "epd.h"
#include "image_transfer.h"
#include "st25dv.h"
#include "status_led.h"
#include "version.h"

#ifndef APP_BUSY_ACTIVE_LOW
#define APP_BUSY_ACTIVE_LOW 1u
#endif
const char g_firmware_id[]="nfc-epaper-" NFC_EPAPER_VERSION;
volatile app_debug_t g_debug={.magic=0x45504431u,.firmware_id=g_firmware_id};
static uint32_t last_refresh_end;
static bool have_refreshed;

void HardFault_Handler(void)
{
    g_debug.fault=1u;
    GPIOA->BSRR=2u;
    for (;;) { __NOP(); }
}

static int nfc_start(void)
{
    int result=-1;
    for (unsigned attempt=0;attempt<8u;++attempt) {
        uint8_t uid[8];
        result=st25_read_uid(uid);
        if (!result) { result=st25_configure_image_link(); }
        if (!result) {
            static const char hex[]="0123456789ABCDEF";
            for (unsigned i=0;i<8u;++i) {
                g_debug.uid_lsb_first[i]=uid[i];
                g_debug.uid_hex[2u*i]=hex[uid[7u-i]>>4];
                g_debug.uid_hex[2u*i+1u]=hex[uid[7u-i]&15u];
            }
            g_debug.uid_hex[16]=0;
            break;
        }
        board_delay_ms(50u);
    }
    g_debug.nfc_error=result;
    g_debug.nfc_ready=result==0;
    return result;
}

static void refresh_image(const uint8_t *frame)
{
    g_debug.refresh_active=1u;
    status_led_set(LED_REFRESH);
    if (have_refreshed) {
        uint32_t elapsed=(uint32_t)(board_millis()-last_refresh_end);
        if (elapsed<5000u) { board_delay_ms(5000u-elapsed); }
    }
    g_debug.refresh_start_ms=board_millis();
    g_debug.epd_error=epd_show_frame(frame,EPD_FRAME_BYTES,APP_BUSY_ACTIVE_LOW);
    last_refresh_end=board_millis();have_refreshed=true;
    g_debug.refresh_end_ms=last_refresh_end;
    g_debug.refresh_busy_ms=epd_refresh_duration_ms;
    g_debug.refresh_busy_glitches=epd_busy_glitches;
    if (image_transfer_refresh_finished(g_debug.epd_error,
        (uint32_t)(last_refresh_end-g_debug.refresh_start_ms),
        g_debug.reset_flags,last_refresh_end)) {
        image_link_reply_ready(last_refresh_end);
    }
    g_debug.refresh_active=0u;
    status_led_set(LED_IDLE);
    if (g_debug.epd_error) { status_led_error(); }
    else { ++g_debug.refresh_count; }
}

static void update_indicators(uint32_t *reported_errors)
{
    uint32_t errors=g_image_debug.protocol_errors+g_image_debug.link_failures+g_image_debug.timeouts;
    if (*reported_errors!=errors) {
        *reported_errors=errors;
        if (errors) { status_led_error(); }
    }
    status_led_set(g_debug.refresh_active ? LED_REFRESH : !g_debug.nfc_ready ? LED_ERROR :
                   g_image_debug.state ? LED_TRANSFER : LED_IDLE);
}

int main(void)
{
    g_debug.reset_flags=board_take_reset_flags();
    board_init(); /* Holds screen RESET until an accepted image starts a job. */
    (void)nfc_start();
    image_link_init();
    uint32_t heartbeat_at=0u,reported_errors=0u;
    for (;;) {
        uint32_t now=board_millis();
        g_debug.uptime_ms=now;
        g_debug.busy_pin=pin_read(GPIOA,EPD_BUSY);
        g_debug.gpo_pin=pin_read(GPIOB,NFC_GPO);
        g_debug.nfc_gpo_events=board_nfc_write_events();
        if ((uint32_t)(now-heartbeat_at)>=500u) {
            heartbeat_at=now;++g_debug.heartbeat;
        }
        if (g_debug.nfc_ready) {
            image_link_service(now);
            const uint8_t *frame=image_transfer_take_frame();
            if (frame) { refresh_image(frame); }
        }
        update_indicators(&reported_errors);
        board_delay_ms(10u);
    }
}
