#ifndef STATUS_LED_H
#define STATUS_LED_H
#include <stdint.h>
typedef enum { LED_IDLE, LED_TRANSFER, LED_REFRESH, LED_ERROR } status_led_mode_t;
void status_led_set(status_led_mode_t mode);
void status_led_error(void);
/* Called by SysTick, so indication continues during blocking panel waits. */
void status_led_tick(uint32_t now);
#endif
