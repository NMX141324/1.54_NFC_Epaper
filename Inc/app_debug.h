#ifndef APP_DEBUG_H
#define APP_DEBUG_H
#include <stdint.h>
typedef struct {
    uint32_t magic;
    uint32_t stage;             /* RW: 1 EPD only, 2 +UID, 3 +phone command */
    uint32_t command;           /* RW: 1 bars,2 white,3 UID,4 image,5 checker,6 "1234" */
    uint32_t busy_active_low;   /* RW: 1 supplied code; 0 spec. Measure first. */
    uint32_t uptime_ms;
    uint32_t heartbeat;
    uint32_t nfc_present;
    int32_t nfc_error;
    uint8_t uid_lsb_first[8];
    char uid_hex[17];
    uint32_t refresh_count;
    int32_t epd_error;
    uint32_t command_count;
    uint32_t last_sequence;
    uint32_t last_opcode;
    uint32_t last_status;       /* 0 done,1 reserved,2 bad opcode,3 UID,4 EPD */
    int32_t ack_error;
    uint32_t protocol_armed;
    uint32_t busy_pin;
    uint32_t gpo_pin;
    uint32_t fault;
    uint32_t boot_phase;        /* 4 waiting; no automatic boot image */
    uint32_t boot_colors_done_ms;
    uint32_t boot_uid_start_ms; /* Reserved: automatic UID screen removed, stays 0 */
    uint32_t reset_flags;       /* RCC_CSR snapshot at boot, before clearing RMVF */
    const char *firmware_id;
    uint32_t nfc_gpo_ready;
    int32_t nfc_init_error;
    uint32_t nfc_gpo_events;
    uint32_t nfc_pending;
    uint32_t nfc_read_attempts;
    uint32_t nfc_read_errors;
    uint8_t nfc_last_packet[4];
    uint32_t nfc_packet_result; /* 0 none,1 bad packet,2 old seq,3 accepted,4 changed while reading */
    uint32_t nfc_boot_sequence; /* 0..255, or 256 if no valid command at boot */
    uint32_t refresh_active,refresh_start_ms,refresh_end_ms;
    uint32_t refresh_busy_ms,refresh_busy_glitches;
} app_debug_t;
extern volatile app_debug_t g_debug;
#endif
