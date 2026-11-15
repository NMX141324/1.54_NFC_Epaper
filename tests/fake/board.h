#ifndef BOARD_H
#define BOARD_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef struct { uint32_t BSRR; } GPIO_TypeDef;
extern GPIO_TypeDef fake_gpio;
#define GPIOA (&fake_gpio)
#define GPIOB (&fake_gpio)
#define NFC_GPO 3u
#define __NOP() __asm__ volatile("nop")
#define EPD_DC 5u
#define EPD_CS 4u
#define EPD_RST 6u
#define EPD_BUSY 7u
void pin_write(GPIO_TypeDef *p,unsigned pin,bool high);
bool pin_read(GPIO_TypeDef *p,unsigned pin);
uint32_t board_millis(void);
void board_delay_ms(uint32_t ms);
void board_spi_byte(uint8_t b);
void board_init(void);
uint32_t board_take_reset_flags(void);
uint32_t board_nfc_write_events(void);
void board_led(unsigned led,bool on);
int board_i2c_read(uint8_t addr,uint16_t reg,uint8_t *data,size_t len);
int board_i2c_write(uint8_t addr,uint16_t reg,const uint8_t *data,size_t len);
#endif
