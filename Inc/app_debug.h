#ifndef APP_DEBUG_H
#define APP_DEBUG_H
#include <stdint.h>
/* Operational diagnostics available through ST-Link. No display test commands. */
typedef struct {
    uint32_t magic;
    const char *firmware_id;
    uint32_t reset_flags;
    uint32_t uptime_ms,heartbeat;
    uint32_t nfc_ready;
    int32_t nfc_error;
    uint8_t uid_lsb_first[8];
    char uid_hex[17];
    uint32_t nfc_gpo_events;
    uint32_t busy_pin,gpo_pin;
    uint32_t refresh_count,refresh_active;
    uint32_t refresh_start_ms,refresh_end_ms;
    uint32_t refresh_busy_ms,refresh_busy_glitches;
    int32_t epd_error;
    uint32_t fault;
} app_debug_t;
extern volatile app_debug_t g_debug;
#endif
