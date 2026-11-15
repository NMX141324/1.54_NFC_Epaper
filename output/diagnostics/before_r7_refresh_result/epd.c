#include "epd.h"
#include "board.h"
#include "vendor_image.h"

volatile uint32_t epd_phase;
volatile uint32_t epd_bytes_sent;
volatile uint32_t epd_busy_level;
static uint32_t active_low;

static void command(uint8_t b)
{
    pin_write(GPIOA,EPD_DC,false); pin_write(GPIOA,EPD_CS,false);
    board_spi_byte(b); pin_write(GPIOA,EPD_CS,true);
}
static void data(uint8_t b)
{
    pin_write(GPIOA,EPD_DC,true); pin_write(GPIOA,EPD_CS,false);
    board_spi_byte(b); pin_write(GPIOA,EPD_CS,true);
}
static int ready(uint32_t timeout)
{
    uint32_t t=board_millis();
    do {
        epd_busy_level=pin_read(GPIOA,EPD_BUSY)?1u:0u;
        if (epd_busy_level != (active_low ? 0u:1u)) { return 0; }
        board_delay_ms(1u);
    } while ((uint32_t)(board_millis()-t)<timeout);
    return -(int)epd_phase;
}
static int refresh_started(void)
{
    uint32_t start=board_millis();
    do {
        epd_busy_level=pin_read(GPIOA,EPD_BUSY)?1u:0u;
        if (epd_busy_level==(active_low?0u:1u)) { return 0; }
        board_delay_ms(1u);
    } while ((uint32_t)(board_millis()-start)<1000u);
    return -9; /* No BUSY assertion: disconnected panel or rejected command. */
}
static const uint8_t hexfont[16][5]={
 {0x3e,0x51,0x49,0x45,0x3e},{0,0x42,0x7f,0x40,0},
 {0x42,0x61,0x51,0x49,0x46},{0x21,0x41,0x45,0x4b,0x31},
 {0x18,0x14,0x12,0x7f,0x10},{0x27,0x45,0x45,0x45,0x39},
 {0x3c,0x4a,0x49,0x49,0x30},{1,0x71,9,5,3},
 {0x36,0x49,0x49,0x49,0x36},{6,0x49,0x49,0x29,0x1e},
 {0x7e,0x11,0x11,0x11,0x7e},{0x7f,0x49,0x49,0x49,0x36},
 {0x3e,0x41,0x41,0x41,0x22},{0x7f,0x41,0x41,0x22,0x1c},
 {0x7f,0x49,0x49,0x49,0x41},{0x7f,9,9,9,1}
};
static uint8_t pixel(uint32_t pattern, unsigned x, unsigned y, const uint8_t uid[8])
{
    static const uint8_t palette[6]={0,1,2,3,5,6};
    if (pattern==1u) { return palette[x/40u]; }
    if (pattern==2u) { return 1u; }
    if (pattern==5u) { return ((x/20u+y/20u)&1u)?0u:1u; }
    if (pattern==6u) {
        /* Large centered black "1234" on white, 5x7 font at scale 8. */
        if (x>=24u && x<216u && y>=92u && y<148u) {
            unsigned digit=1u+(x-24u)/48u;
            unsigned gx=((x-24u)%48u)/8u,gy=(y-92u)/8u;
            if (gx<5u && ((hexfont[digit][gx]>>gy)&1u)) { return 0u; }
        }
        return 1u;
    }
    /* UID: two lines, eight hex digits each, MSB first; scale 4. */
    if (y>=180u && y<220u) { return palette[x/40u]; }
    if (x>=24u && x<216u && ((y>=50u && y<78u) || (y>=100u && y<128u))) {
        unsigned line=y>=100u?1u:0u;
        unsigned col=(x-24u)/24u, gx=((x-24u)%24u)/4u;
        unsigned gy=(y-(line?100u:50u))/4u;
        unsigned digit=line*8u+col;
        uint8_t byte=uid[7u-digit/2u];
        unsigned hex=(digit&1u)?(byte&15u):(byte>>4);
        if (gx<5u && ((hexfont[hex][gx]>>gy)&1u)) { return 0u; }
    }
    return 1u;
}
static int show(uint32_t pattern,const uint8_t uid[8],const uint8_t *pixels,uint32_t busy_active_low)
{
    active_low=busy_active_low;
    epd_phase=1u; epd_bytes_sent=0u;
    pin_write(GPIOA,EPD_RST,false); board_delay_ms(20u);
    pin_write(GPIOA,EPD_RST,true); board_delay_ms(30u);
    int r=ready(15000u); if (r) { return r; }
    epd_phase=2u;
    /* Normal OTP waveform sequence from supplied ESP-IDF sample. */
    command(0xe9u); data(0x01u); board_delay_ms(10u);
    r=ready(15000u); if (r) { return r; }
    epd_phase=3u; command(0x10u);
    r=ready(15000u); if (r) { return r; }
    for (unsigned y=0;y<EPD_HEIGHT;++y) {
        for (unsigned x=0;x<EPD_WIDTH;x+=2u) {
            uint8_t b=pixels ? pixels[epd_bytes_sent] : (pattern==4u ? vendor_image[epd_bytes_sent] :
                (uint8_t)((pixel(pattern,x,y,uid)<<4)|pixel(pattern,x+1u,y,uid)));
            data(b); ++epd_bytes_sent;
        }
    }
    epd_phase=4u; command(0x04u); board_delay_ms(10u);
    r=ready(60000u); if (r) { goto fail; }
    epd_phase=5u; command(0x12u); data(0x00u); board_delay_ms(10u);
    r=refresh_started(); if (r) { goto fail; }
    r=ready(120000u); if (r) { goto fail; }
    epd_phase=6u; command(0x02u); data(0x00u); board_delay_ms(10u);
    r=ready(60000u); if (r) { goto fail; }
    board_delay_ms(20u);
    epd_phase=7u; command(0x07u); data(0xa5u);
    epd_phase=0u;
    return 0;
fail:
    /* Preserve failed phase. Reset only, no commands into unknown BUSY state.
     * If HV remains present, disconnect external supply. */
    pin_write(GPIOA,EPD_RST,false);
    return r;
}
int epd_show(uint32_t pattern,const uint8_t uid[8],uint32_t busy_active_low)
{
    if (pattern<1u || pattern>6u) { return -8; }
    return show(pattern,uid,0,busy_active_low);
}
int epd_show_frame(const uint8_t *pixels,uint32_t length,uint32_t busy_active_low)
{
    if (!pixels || length!=EPD_FRAME_BYTES) { return -8; }
    for (uint32_t i=0;i<length;++i) {
        unsigned a=pixels[i]>>4,b=pixels[i]&15u;
        if (a>6u || a==4u || b>6u || b==4u) { return -8; }
    }
    return show(0u,0,pixels,busy_active_low);
}
