#include "board.h"
#include "status_led.h"
#include "epd.h"

uint32_t SystemCoreClock = 16000000u;
static volatile uint32_t ticks;
static volatile uint32_t nfc_write_events;
static volatile bool leds_ready;

/* Called before .data/.bss initialization. No initialized globals here. */
void SystemInit(void)
{
    RCC->CR |= RCC_CR_HSION;
    while (!(RCC->CR & RCC_CR_HSIRDY)) { }
    RCC->CR &= ~RCC_CR_HSIDIV;
    RCC->CFGR = 0u; /* HSI16, AHB /1, APB /1; no external crystal. */
    while (RCC->CFGR & RCC_CFGR_SWS) { }
    SCB->VTOR = FLASH_BASE;
}

void SystemCoreClockUpdate(void) { SystemCoreClock = 16000000u; }
uint32_t board_take_reset_flags(void)
{
    uint32_t flags=RCC->CSR;
    /* Snapshot after C runtime initialization, then clear sticky causes so
     * the next boot can be distinguished from this one. No reset requested. */
    RCC->CSR |= RCC_CSR_RMVF;
    return flags;
}
void SysTick_Handler(void)
{
    ++ticks;
    /* 10 ms resolution is enough for indicators; keep RF/SPI timing jitter low. */
    if (leds_ready && ticks%10u==0u) { status_led_tick(ticks); }
}
void EXTI2_3_IRQHandler(void)
{
    if (EXTI->FPR1 & (1u<<NFC_GPO)) {
        EXTI->FPR1=1u<<NFC_GPO;
        ++nfc_write_events;
    }
}
uint32_t board_nfc_write_events(void) { return nfc_write_events; }
uint32_t board_millis(void) { return ticks; }
void board_delay_ms(uint32_t ms)
{
    uint32_t start = ticks;
    while ((uint32_t)(ticks - start) < ms) { __WFI(); }
}

/* SysTick core-clock countdown supplies timing in Debug and Release alike. */
void board_delay_us(uint32_t us)
{
    while (us--) {
        uint32_t prev = SysTick->VAL, elapsed = 0u;
        while (elapsed < 16u) {
            uint32_t now = SysTick->VAL;
            elapsed += prev >= now ? prev - now : prev + 16000u - now;
            prev = now;
        }
    }
}

static void mode(GPIO_TypeDef *p, unsigned n, unsigned m)
{
    p->MODER = (p->MODER & ~(3u << (2u*n))) | (m << (2u*n));
    p->PUPDR &= ~(3u << (2u*n));
}

void board_init(void)
{
    SysTick_Config(SystemCoreClock / 1000u);
    RCC->IOPENR |= RCC_IOPENR_GPIOAEN | RCC_IOPENR_GPIOBEN;
    (void)RCC->IOPENR;
    /* Set output latches before enabling drivers; preserve PA13/PA14 SWD. */
    /* Keep the panel in RESET after every MCU boot. Interrupted waveforms
     * are never resumed from whatever the controller retained across a dip. */
    GPIOA->BSRR = (1u << EPD_CS) | (1u << EPD_DC) | (1u << (EPD_RST+16u));
    for (unsigned n = 0; n <= EPD_RST; ++n) { mode(GPIOA,n,1u); }
    epd_hold_reset();
    mode(GPIOA,EPD_BUSY,0u);
    leds_ready=true;
    status_led_set(LED_IDLE);
    mode(GPIOB,NFC_GPO,0u);
    /* ST25DV04K-IE GPO is open drain, active LOW (external 10k pull-up).
     * Capture ~188 us pulses even while the panel is refreshing. */
    EXTI->EXTICR[0]=(EXTI->EXTICR[0] & ~EXTI_EXTICR1_EXTI3_Msk) |
                    EXTI_EXTICR1_EXTI3_0; /* EXTI3 = PB3 */
    EXTI->RTSR1 &= ~(1u<<NFC_GPO);
    EXTI->FTSR1 |= 1u<<NFC_GPO;
    EXTI->FPR1=1u<<NFC_GPO;
    EXTI->RPR1=1u<<NFC_GPO;
    EXTI->IMR1 |= 1u<<NFC_GPO;
    NVIC_SetPriority(EXTI2_3_IRQn,2u);
    NVIC_EnableIRQ(EXTI2_3_IRQn);
    board_i2c_init();
}

void board_led(unsigned led, bool on) { pin_write(GPIOA,led == 1u ? 0u : 1u,on); }

/* Mode 0, MSB first. PA2/PA3 are NOT SPI1 MOSI/SCK in this schematic. */
void board_spi_byte(uint8_t byte)
{
    for (unsigned i=0; i<8u; ++i) {
        pin_write(GPIOA,EPD_MOSI,(byte & 0x80u) != 0u);
        board_delay_us(1u);
        pin_write(GPIOA,EPD_CLK,true);
        board_delay_us(1u);
        pin_write(GPIOA,EPD_CLK,false);
        byte <<= 1;
    }
}

/* Conservative software I2C with external 10k pull-ups, clock stretching,
 * repeated START and bounded waits. 0=OK, -1=NACK, -2=bus/timeout. */
static void sda(bool high) { pin_write(GPIOB,NFC_SDA,high); }
static void scl_low(void) { pin_write(GPIOB,NFC_SCL,false); }
static void half(void) { board_delay_us(5u); }
static int scl_high(void)
{
    pin_write(GPIOB,NFC_SCL,true);
    uint32_t t=board_millis();
    while (!pin_read(GPIOB,NFC_SCL)) {
        if ((uint32_t)(board_millis()-t) >= 10u) { return -2; }
    }
    half();
    return 0;
}
static int stop(void)
{
    scl_low(); sda(false); half();
    int r=scl_high();
    sda(true); half();
    return r;
}
void board_i2c_init(void)
{
    GPIOB->BSRR = (1u << NFC_SCL) | (1u << NFC_SDA);
    GPIOB->OTYPER |= (1u << NFC_SCL) | (1u << NFC_SDA);
    mode(GPIOB,NFC_SCL,1u); mode(GPIOB,NFC_SDA,1u);
    /* Recover a slave left mid-byte after MCU-only reset. */
    for (unsigned i=0; i<9u && !pin_read(GPIOB,NFC_SDA); ++i) {
        scl_low(); half(); if (scl_high()) { break; }
    }
    (void)stop();
}
static int start(void)
{
    sda(true); half();
    if (scl_high()) { return -2; }
    if (!pin_read(GPIOB,NFC_SDA)) { return -2; }
    sda(false); half(); scl_low();
    return 0;
}
static int send(uint8_t b)
{
    for (unsigned i=0; i<8u; ++i) {
        sda((b & 0x80u)!=0u); half();
        if (scl_high()) { return -2; }
        scl_low(); b <<= 1;
    }
    sda(true); half();
    if (scl_high()) { return -2; }
    int r=pin_read(GPIOB,NFC_SDA) ? -1 : 0;
    scl_low();
    return r;
}
static int receive(uint8_t *b, bool ack)
{
    uint8_t value=0u;
    sda(true);
    for (unsigned i=0; i<8u; ++i) {
        half(); if (scl_high()) { return -2; }
        value=(uint8_t)((value<<1) | (pin_read(GPIOB,NFC_SDA)?1u:0u));
        scl_low();
    }
    sda(!ack); half();
    if (scl_high()) { return -2; }
    scl_low(); sda(true); *b=value;
    return 0;
}
static int address(uint8_t addr, uint16_t reg)
{
    int r=start();
    if (!r) { r=send((uint8_t)(addr<<1)); }
    if (!r) { r=send((uint8_t)(reg>>8)); }
    if (!r) { r=send((uint8_t)reg); }
    return r;
}
int board_i2c_read(uint8_t addr7, uint16_t reg, uint8_t *data, size_t len)
{
    if (!data || !len) { return -2; }
    int r=address(addr7,reg);
    if (!r) { r=start(); }
    if (!r) { r=send((uint8_t)((addr7<<1)|1u)); }
    for (size_t i=0; !r && i<len; ++i) { r=receive(&data[i],i+1u<len); }
    int end=stop();
    if (r==-2 || end) { board_i2c_init(); }
    return r ? r : end;
}
int board_i2c_write(uint8_t addr7, uint16_t reg, const uint8_t *data, size_t len)
{
    /* A mailbox message must be sent in ONE transaction, including all bytes.
     * Present Password uses 17 bytes; other EEPROM callers retain that cap. */
    if (!data || !len || len>(reg==0x2008u?256u:17u)) { return -2; }
    int r=address(addr7,reg);
    for (size_t i=0; !r && i<len; ++i) { r=send(data[i]); }
    int end=stop();
    if (r==-2 || end) { board_i2c_init(); }
    return r ? r : end;
}
