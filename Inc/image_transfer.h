#ifndef IMAGE_TRANSFER_H
#define IMAGE_TRANSFER_H
#include <stdbool.h>
#include <stdint.h>
#define IMAGE_HEADER_BYTES 32u
#define IMAGE_PIXEL_BYTES 28800u
#define IMAGE_FILE_BYTES (IMAGE_HEADER_BYTES+IMAGE_PIXEL_BYTES)
typedef struct {
    uint32_t state,received,total,packets,segments,retries,errors;
    uint32_t command,last_control,last_status,peer_crc;
    uint32_t image_id,image_crc,image_valid,refresh_ready,tx_bytes,timeouts;
    uint32_t mailbox_control;
    int32_t link_error;
    /* Recoverable arbitration/CRC retries are separate from terminal faults. */
    uint32_t link_retries,link_failures,protocol_errors,crc_retries;
    uint32_t refresh_state,refresh_elapsed_ms;
    int32_t refresh_error;
} image_debug_t;
extern volatile image_debug_t g_image_debug;
uint32_t image_crc32(const uint8_t *p,uint32_t n);
int image_file_validate(const uint8_t *p,uint32_t n);
void image_transfer_init(void);
int image_transfer_receive(const uint8_t *p,uint32_t n,uint32_t now);
bool image_transfer_next_tx(uint8_t p[256],uint16_t *n);
void image_transfer_sent(uint32_t now);
void image_transfer_released(uint32_t now);
bool image_transfer_inflight(void);
bool image_transfer_tick(uint32_t now);
void image_transfer_link_closed(void);
const uint8_t *image_transfer_take_frame(void);
bool image_transfer_refresh_finished(int error,uint32_t elapsed_ms,uint32_t reset_flags,uint32_t now);
void image_link_init(void);
void image_link_reply_ready(uint32_t now);
/* Event-driven mailbox service; idle never polls I2C. */
void image_link_service(uint32_t now);
#endif
