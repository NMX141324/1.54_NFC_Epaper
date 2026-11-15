#ifndef BOARD_H
#define BOARD_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "stm32g070xx.h"

#define EPD_MOSI 2u
#define EPD_CLK  3u
#define EPD_CS   4u
#define EPD_DC   5u
#define EPD_RST  6u
#define EPD_BUSY 7u
#define NFC_SCL  6u /* PB6 */
#define NFC_SDA  7u /* PB7 */
#define NFC_GPO  3u /* PB3 */

void board_init(void);
uint32_t board_take_reset_flags(void);
uint32_t board_nfc_write_events(void);
uint32_t board_millis(void);
void board_delay_ms(uint32_t ms);
void board_delay_us(uint32_t us);
void board_led(unsigned led, bool on);
void board_spi_byte(uint8_t byte);
void board_i2c_init(void);
int board_i2c_read(uint8_t addr7, uint16_t reg, uint8_t *data, size_t len);
int board_i2c_write(uint8_t addr7, uint16_t reg, const uint8_t *data, size_t len);
static inline void pin_write(GPIO_TypeDef *port, unsigned pin, bool high)
{
    port->BSRR = 1u << (pin + (high ? 0u : 16u));
}
static inline bool pin_read(GPIO_TypeDef *port, unsigned pin)
{
    return (port->IDR & (1u << pin)) != 0u;
}
#endif
