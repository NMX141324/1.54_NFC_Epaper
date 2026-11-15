/* Included by firmware_test.c: independent sender fixtures + actual modules. */
#include "build/image_fixtures.h"
static void image_reply(uint8_t cmd,uint8_t status)
{
    uint8_t p[256];uint16_t n=0u;
    CHECK(image_transfer_next_tx(p,&n));
    CHECK(n>=4u && p[0]==0x40u && p[2]==(uint8_t)(cmd|0x80u) && p[3]==status);
    image_transfer_sent(ticks);image_transfer_released(ticks);
}
static void image_password(void)
{
    uint8_t p[]={0x40,5,7,0x12,0x34,0x56,0x78};
    CHECK(image_transfer_receive(p,sizeof p,ticks)==0);image_reply(7u,0x81u);
}
static void image_ack(uint8_t expected)
{
    uint8_t p[256];uint16_t n=0u;
    CHECK(image_transfer_next_tx(p,&n) && n==1u && p[0]==expected);
    image_transfer_sent(ticks);image_transfer_released(ticks);
}
static void send_image_wire(bool corrupt_first,bool duplicate_second)
{
    unsigned offset=0u,segment_begin=0u,segments=0u;
    bool corrupted=false;
    while (offset<sizeof test_image_wire) {
        unsigned n=(unsigned)test_image_wire[offset]|((unsigned)test_image_wire[offset+1u]<<8);
        uint8_t p[256];memcpy(p,test_image_wire+offset+2u,n);
        if (corrupt_first && !corrupted) { p[6]^=1u;corrupted=true; }
        CHECK(image_transfer_receive(p,n,ticks)==0);ticks+=10u;
        unsigned next=offset+2u+n;
        if (p[0]&0x20u) {
            if (corrupt_first && !segments) {
                image_ack(0x81u);corrupt_first=false;offset=segment_begin;continue;
            }
            image_ack(0x80u);++segments;
            if (duplicate_second && (segments==2u || next==sizeof test_image_wire)) {
                /* Duplicate a fully acknowledged middle segment, as after
                 * ACK loss: do not append its bytes a second time. */
                unsigned d=segment_begin;
                while (d<next) {
                    unsigned dn=(unsigned)test_image_wire[d]|((unsigned)test_image_wire[d+1u]<<8);
                    CHECK(image_transfer_receive(test_image_wire+d+2u,dn,ticks)==0);
                    d+=2u+dn;
                }
                image_ack(0x80u);
            }
            segment_begin=next;
        }
        offset=next;
    }
}
static void send_image_command(uint8_t cmd,bool corrupt)
{
    unsigned offset=0u,total=1u+sizeof test_image_file;
    while (offset<total) {
        uint8_t p[256];unsigned amount=total-offset;
        if (amount>200u) { amount=200u; }
        uint8_t pos=!offset?1u:(offset+amount==total?3u:2u);
        unsigned h=pos==1u?6u:2u;
        p[0]=(uint8_t)(0x40u|(pos<<2));p[1]=(uint8_t)amount;
        if (pos==1u) {
            p[2]=(uint8_t)(total>>24);p[3]=(uint8_t)(total>>16);
            p[4]=(uint8_t)(total>>8);p[5]=(uint8_t)total;
        }
        for (unsigned i=0;i<amount;++i) {
            p[h+i]=offset+i?test_image_file[offset+i-1u]:cmd;
            if (corrupt && offset+i==100u) { p[h+i]^=1u; }
        }
        CHECK(image_transfer_receive(p,h+amount,ticks)==0);offset+=amount;ticks+=10u;
    }
}
static void send_image_noack(bool corrupt) { send_image_command(4u,corrupt); }
static uint8_t readback_bytes[IMAGE_FILE_BYTES+2u];
static void image_protocol_test(void)
{
    CHECK(image_crc32((const uint8_t*)"123456789",9u)==0xcbf43926u);
    CHECK(image_file_validate(test_image_file,sizeof test_image_file)==0);
    CHECK(image_file_validate(test_image_file,sizeof test_image_file-1u)==-20);
    for (unsigned i=0;i<4u;++i) {
        image_transfer_init();
        CHECK(image_transfer_receive(test_crc_profiles+i*11u,11u,ticks)==0);
        image_ack(0x80u);CHECK(g_image_debug.crc_profile==i+1u);
        image_reply(7u,0x81u);
    }
    image_transfer_init();image_password();
    send_image_wire(true,true); /* CRC corruption/resend and duplicate segment */
    CHECK(g_image_debug.received==IMAGE_FILE_BYTES+1u && g_image_debug.image_valid==1u);
    CHECK(g_image_debug.crc_profile==4u && g_image_debug.retries>=2u &&
          g_image_debug.crc_retries>0u && g_image_debug.protocol_errors==0u);
    CHECK(!image_transfer_take_frame()); /* App has not consumed final response. */
    image_reply(4u,0x81u);
    const uint8_t *pixels=image_transfer_take_frame();
    CHECK(pixels && !memcmp(pixels,test_image_file+32u,28800u));
    CHECK(!image_transfer_take_frame());
    reset_epd(0u,1u);
    CHECK(epd_show_frame(pixels,28800u,1u)==0 && frame_count==28800u);
    CHECK(!memcmp(frame,test_image_file+32u,28800u));
    CHECK(epd_show_frame(pixels,28799u,1u)==-8);

    /* A true large H2R readback, with independently decoded ST25FTM headers. */
    uint8_t read_request[]={0x40,1,6};
    CHECK(image_transfer_receive(read_request,sizeof read_request,ticks)==0);
    unsigned out=0u;
    for (;;) {
        uint8_t p[256];uint16_t n=0u;
        if (!image_transfer_next_tx(p,&n)) { break; }
        unsigned pos=(p[0]>>2)&3u,h=pos==1u?6u:2u;
        CHECK(n==h+p[1] && out+p[1]<=sizeof readback_bytes);
        memcpy(readback_bytes+out,p+h,p[1]);out+=p[1];
        image_transfer_sent(ticks);image_transfer_released(ticks);
    }
    CHECK(out==sizeof readback_bytes && readback_bytes[0]==0x86u && readback_bytes[1]==0x81u);
    CHECK(!memcmp(readback_bytes+2u,test_image_file,IMAGE_FILE_BYTES));

    /* Even with transport recovery OFF, corrupted image data never refreshes. */
    send_image_noack(true);image_reply(4u,0x82u);
    CHECK(!image_transfer_take_frame() && !g_image_debug.image_valid);
    send_image_noack(false);image_reply(4u,0x81u);CHECK(image_transfer_take_frame()!=0);

    image_transfer_init();image_password();
    uint16_t first_length=(uint16_t)test_image_wire[0]|((uint16_t)test_image_wire[1]<<8);
    CHECK(image_transfer_receive(test_image_wire+2u,first_length,ticks)==0);
    CHECK(image_transfer_tick(ticks+15001u) && !image_transfer_take_frame());
    send_image_wire(false,false);image_reply(4u,0x81u);CHECK(image_transfer_take_frame()!=0);

    image_transfer_init();
    uint8_t too_big[]={0x44,1,0xff,0xff,0xff,0xff,4};
    CHECK(image_transfer_receive(too_big,sizeof too_big,ticks)==-24);image_ack(0x83u);
    uint8_t truncated[]={0x71,0xff,0};
    CHECK(image_transfer_receive(truncated,sizeof truncated,ticks)==-24);image_ack(0x83u);
    CHECK(!image_transfer_take_frame());

    image_transfer_init();image_password();send_image_noack(false);
    image_transfer_link_closed();CHECK(!image_transfer_take_frame());
}
static uint32_t result_word(const uint8_t *p)
{ return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }
static void refresh_result_test(void)
{
    image_transfer_init();send_image_command(9u,false);
    CHECK(!image_transfer_take_frame()); /* receiving is not completion */
    image_reply(9u,0x81u);
    CHECK(image_transfer_take_frame()!=0 && !image_transfer_take_frame());
    CHECK(g_image_debug.refresh_state==2u);
    uint8_t out[256];uint16_t length=0u;
    CHECK(!image_transfer_next_tx(out,&length));
    ticks+=35000u;CHECK(!image_transfer_tick(ticks)); /* no 15s transfer timeout during panel job */
    CHECK(image_transfer_refresh_finished(0,35000u,1u<<27,ticks));
    CHECK(!image_transfer_tick(ticks));
    CHECK(image_transfer_next_tx(out,&length) && length==24u);
    CHECK(out[2]==0x89u && out[3]==0x90u);
    CHECK(!memcmp(out+4u,test_image_file+16u,8u)); /* bind to image ID and CRC */
    CHECK(result_word(out+12u)==35000u && result_word(out+16u)==0u && result_word(out+20u)==(1u<<27));
    CHECK(!image_transfer_refresh_finished(0,35000u,0u,ticks)); /* exactly one result */
    bus_error=0;mb_control=1u;image_link_reply_ready(ticks);
    CHECK(image_link_service(ticks) && mb_control==3u && mb_length==24u);
    CHECK(mb_data[3]==0x90u);
    mb_control=1u;mb_irq_status=0x40u;++rf_events;
    (void)image_link_service(ticks);ticks+=5u;(void)image_link_service(ticks);
    CHECK(!image_transfer_next_tx(out,&length));

    image_transfer_init();send_image_command(9u,false);image_reply(9u,0x81u);
    CHECK(image_transfer_take_frame()!=0);
    CHECK(image_transfer_refresh_finished(-10,750u,0u,ticks));
    CHECK(image_transfer_next_tx(out,&length) && out[3]==0x91u);
    CHECK((int32_t)result_word(out+16u)==-10 && g_image_debug.refresh_state==4u);
    image_transfer_init(); /* MCU reset loses RAM job: never replay a frame/result */
    CHECK(!image_transfer_take_frame() && !image_transfer_next_tx(out,&length));
    send_image_command(9u,false);image_reply(9u,0x81u);
    image_transfer_link_closed(); /* phone closes before main dispatch */
    CHECK(!image_transfer_take_frame() && !image_transfer_next_tx(out,&length));
    CHECK(g_image_debug.refresh_error==-26);
}
static void image_hardware_interface_test(void)
{
    /* Fresh K chip: never write MB_EN=0 while MB_MODE is still zero. */
    mb_mode=0u;mb_control=0u;unsigned before=i2c_writes;
    CHECK(st25_mailbox_enable(false)==0 && i2c_writes==before);
    CHECK(st25_configure_image_link()==0 && mb_mode==1u && gpo_config==0xf0u);
    bus_error=0;mb_control=0u;image_link_init();
    unsigned reads=i2c_reads,writes=i2c_writes;
    for (unsigned i=0;i<1000u;++i) { ticks+=10u;CHECK(!image_link_service(ticks)); }
    CHECK(i2c_reads==reads && i2c_writes==writes);
    uint8_t p[]={0x40,5,7,0x12,0x34,0x56,0x78};
    memcpy(mb_data,p,sizeof p);mb_length=sizeof p;mb_control=5u;++rf_events;
    (void)image_link_service(ticks); /* IRQ observed: no immediate I2C contention */
    CHECK(i2c_reads==reads && i2c_writes==writes);
    ticks+=5u;CHECK(image_link_service(ticks));
    CHECK(mb_control==3u && mb_length==4u && mb_data[2]==0x87u && mb_data[3]==0x81u);
    mb_control=1u;++rf_events;ticks+=10u;(void)image_link_service(ticks);
    ticks+=5u;CHECK(image_link_service(ticks));
    CHECK(!image_transfer_inflight());
    bus_error=-1;++rf_events;(void)image_link_service(ticks);
    for (unsigned i=0;i<3u;++i) { ticks+=10u;(void)image_link_service(ticks); }
    CHECK(g_image_debug.link_retries==3u && g_image_debug.link_failures==0u);
    bus_error=0;ticks+=10u;(void)image_link_service(ticks);
    CHECK(g_image_debug.link_error==0 && !g_image_debug.link_failures);
    bus_error=-1;++rf_events;(void)image_link_service(ticks);
    for (unsigned i=0;i<20u;++i) { ticks+=10u;(void)image_link_service(ticks); }
    CHECK(g_image_debug.link_failures==1u);
    reads=i2c_reads;
    for (unsigned i=0;i<30u;++i) { ticks+=10u;(void)image_link_service(ticks); }
    CHECK(i2c_reads==reads);
    bus_error=0;++rf_events;(void)image_link_service(ticks);
    ticks+=10u;(void)image_link_service(ticks);CHECK(g_image_debug.link_error==0);
    uint8_t data[256];uint16_t length=0u;
    for (unsigned i=0;i<256u;++i) { mb_data[i]=(uint8_t)i; }
    mb_length=256u;mb_control=5u;
    CHECK(st25_mailbox_read(data,&length)==0 && length==256u && mb_control==1u);
    for (unsigned i=0;i<256u;++i) { CHECK(data[i]==(uint8_t)i); }
    CHECK(st25_mailbox_write(data,256u)==0 && mb_length==256u && mb_control==3u);
    CHECK(st25_mailbox_write(data,257u)==-2);
    mb_control=0u;++rf_events;ticks+=10u;(void)image_link_service(ticks);
    ticks+=5u;CHECK(!image_link_service(ticks));

    /* App consumes final response and immediately disables MB. The RF_GET
     * interrupt is proof of consumption even though MB_EN is already clear. */
    image_transfer_init();image_password();send_image_noack(false);
    uint8_t response_bytes[256];uint16_t response_length=0u;
    CHECK(image_transfer_next_tx(response_bytes,&response_length));
    image_transfer_sent(ticks);mb_control=0u;mb_irq_status=0x40u;++rf_events;
    ticks+=10u;(void)image_link_service(ticks);ticks+=5u;CHECK(!image_link_service(ticks));
    CHECK(image_transfer_take_frame()!=0);
}
