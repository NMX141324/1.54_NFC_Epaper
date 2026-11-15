/* Exercise the real Cortex-M0+ image receiver using Kotlin-generated APP packets.
 * Included only by tests/firmware_test.c, never by production firmware. */
#if __has_include("../../build/interop/android_image_fixture.h")
#include "../../build/interop/android_image_fixture.h"
static void android_app_interop_test(void)
{
    image_transfer_init();
    uint8_t hello[]={0x40,1,0};
    CHECK(image_transfer_receive(hello,sizeof hello,ticks)==0);
    image_reply(0u,0x81u);
    unsigned offset=0u;
    while (offset<sizeof android_image_wire) {
        unsigned n=(unsigned)android_image_wire[offset]|((unsigned)android_image_wire[offset+1u]<<8);
        CHECK(n>0u && n<=240u && offset+2u+n<=sizeof android_image_wire);
        if (!n || n>240u || offset+2u+n>sizeof android_image_wire) { break; }
        const uint8_t *packet=android_image_wire+offset+2u;
        CHECK(image_transfer_receive(packet,n,ticks)==0);
        ticks+=10u;
        if (packet[0]&0x20u) { image_ack(0x80u); }
        offset+=2u+n;
    }
    CHECK(g_image_debug.image_valid==1u && g_image_debug.crc_profile==4u);
    CHECK(g_image_debug.received==sizeof android_image_file+1u);
    CHECK(!image_transfer_take_frame());
    image_reply(9u,0x81u);
    const uint8_t *pixels=image_transfer_take_frame();
    CHECK(pixels && !memcmp(pixels,android_image_file+32u,28800u));
    if (pixels) {
        reset_epd(0u,1u);
        CHECK(epd_show_frame(pixels,28800u,1u)==0);
        CHECK(frame_count==28800u && !memcmp(frame,android_image_file+32u,28800u));
        CHECK(epd_phase==0u && epd_refresh_duration_ms>=1000u);
        CHECK(image_transfer_refresh_finished(0,epd_refresh_duration_ms,0u,ticks));
        uint8_t result[256];uint16_t result_length=0u;
        CHECK(image_transfer_next_tx(result,&result_length) && result_length==24u);
        CHECK(result[2]==0x89u && result[3]==0x90u);
        CHECK(!memcmp(result+4u,android_image_file+16u,8u));
    }
}
#else
static void android_app_interop_test(void) {}
#endif
