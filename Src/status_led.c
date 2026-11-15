#include "status_led.h"
#include "board.h"

static volatile status_led_mode_t mode;
static volatile uint32_t error_at;
static volatile bool error_active;

void status_led_tick(uint32_t now)
{
    if (error_active && (uint32_t)(now-error_at)>=3000u) { error_active=false; }
    status_led_mode_t current=mode;
    if (error_active && current!=LED_REFRESH) { current=LED_ERROR; }
    bool led1=false,led2=false;
    switch (current) {
    case LED_IDLE: led1=(now%2000u)<100u; break;
    case LED_TRANSFER: led1=(now%200u)<100u; break;
    case LED_REFRESH: led2=true; break;
    case LED_ERROR: led2=(now%500u)<250u; break;
    }
    board_led(1u,led1);
    board_led(2u,led2);
}
void status_led_set(status_led_mode_t next)
{
    mode=next;
    status_led_tick(board_millis());
}
void status_led_error(void)
{
    error_at=board_millis();
    error_active=true;
    status_led_tick(error_at);
}
