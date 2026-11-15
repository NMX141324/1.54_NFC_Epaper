#ifndef EPD_H
#define EPD_H
#include <stdint.h>
#define EPD_WIDTH 240u
#define EPD_HEIGHT 240u
#define EPD_FRAME_BYTES (EPD_WIDTH*EPD_HEIGHT/2u)
/* 0 idle, 1 reset, 2 init, 3 frame, 4 power on, 5 refresh, 6 off, 7 sleep */
extern volatile uint32_t epd_phase;
extern volatile uint32_t epd_bytes_sent;
extern volatile uint32_t epd_busy_level;
extern volatile uint32_t epd_busy_glitches,epd_refresh_duration_ms;
extern volatile uint32_t epd_reset_held;
/* Stop/reinitialize the controller without starting a waveform or sending SPI. */
void epd_hold_reset(void);
/* busy_active_low follows supplied sample (1); specification says 0.
 * Confirm on actual panel. Never auto-invert/ignore BUSY. */
/* Patterns: 1 colors,2 white,3 UID,4 vendor image,5 checker,6 "1234". */
int epd_show(uint32_t pattern, const uint8_t uid[8], uint32_t busy_active_low);
int epd_show_frame(const uint8_t *pixels,uint32_t length,uint32_t busy_active_low);
#endif
