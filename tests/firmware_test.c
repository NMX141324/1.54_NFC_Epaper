#include "board.h"
#include "command.h"
#include "epd.h"
#include "st25dv.h"

/* Exercise the real startup and command dispatch, with GPIO/I2C mocked. */
#define main firmware_main
#include "../Src/main.c"
#undef main

GPIO_TypeDef fake_gpio;
void *memset(void *dst,int value,size_t n)
{
    unsigned char *d=dst;
    for(size_t i=0;i<n;++i) { d[i]=(unsigned char)value; }
    return dst;
}
void *memcpy(void *dst,const void *src,size_t n)
{
    unsigned char *d=dst; const unsigned char *s=src;
    for(size_t i=0;i<n;++i) { d[i]=s[i]; }
    return dst;
}
int memcmp(const void *left,const void *right,size_t n)
{
    const unsigned char *a=left,*b=right;
    for (size_t i=0;i<n;++i) { if (a[i]!=b[i]) { return (int)a[i]-(int)b[i]; } }
    return 0;
}
static unsigned failures;
#define CHECK(x) do { if (!(x)) { ++failures; } } while (0)
static uint32_t ticks, phase_time, old_phase, busy_mode, polarity;
static bool dc,panel_reset_high;
static bool led_state[2],monitor_refresh;
static unsigned panel_pin_writes;
static unsigned byte_count,commands,frame_count;
static uint8_t frame[28800], cmdlog[32], datalog[32];
static uint8_t mem_uid[8]={1,2,3,4,5,0x24,2,0xe0},ack_mem[4];
static int bus_error;
static unsigned ack_writes;
static unsigned uid_reads;
static uint32_t rf_events;
static unsigned i2c_reads,i2c_writes;
static uint8_t mem_cmd[4]={10,11,12,13};
static uint8_t gpo_config=0x88u,gpo_dynamic=0x88u,security_session;
static bool wrong_password,event_on_read;
static unsigned config_writes,password_presents;
static uint8_t mb_mode,mb_watchdog=7u,mb_control,mb_data[256];
static uint16_t mb_length;
static uint8_t mb_irq_status;
uint32_t board_nfc_write_events(void) { return rf_events; }
uint32_t board_millis(void) { return ticks; }
void board_delay_ms(uint32_t ms) { ticks+=ms;status_led_tick(ticks); }
void board_init(void) { }
uint32_t board_take_reset_flags(void) { return 0u; }
void board_led(unsigned led,bool on) { led_state[led-1u]=on; }
void pin_write(GPIO_TypeDef *p,unsigned pin,bool high)
{
    (void)p;
    ++panel_pin_writes;
    if (pin==EPD_RST) { panel_reset_high=high; }
    if (pin==EPD_DC) { dc=high; }
}
bool pin_read(GPIO_TypeDef *p,unsigned pin)
{
    (void)p; (void)pin;
    if (epd_phase!=old_phase) { old_phase=epd_phase; phase_time=ticks; }
    bool busy=false;
    if (busy_mode==1u) { busy=true; }
    else if ((busy_mode==0u || busy_mode==3u || busy_mode==4u) && epd_phase==5u) {
        uint32_t elapsed=(uint32_t)(ticks-phase_time);
        busy=elapsed<(busy_mode==4u?50u:1500u);
        if (busy_mode==3u && ((elapsed>=100u && elapsed<120u) ||
                            (elapsed>=500u && elapsed<600u))) { busy=false; }
    }
    return polarity ? !busy : busy;
}
void board_spi_byte(uint8_t b)
{
    CHECK(panel_reset_high);
    if (monitor_refresh) { CHECK(!led_state[0] && led_state[1]); }
    ++byte_count;
    if (!dc) { cmdlog[commands++]=b; }
    else if (epd_phase==3u) { frame[frame_count++ % 28800u]=b; }
    else { datalog[commands-1u]=b; }
}
int board_i2c_read(uint8_t addr,uint16_t reg,uint8_t *data,size_t len)
{
    ++i2c_reads;
    if (bus_error) { return bus_error; }
    if (reg==0x18u) {
        ++uid_reads;
        CHECK(addr==0x57u && len==8u);
        for (unsigned i=0;i<len;++i) { data[i]=mem_uid[i]; }
    } else if (reg==0x40u) {
        CHECK(addr==0x53u && len==4u);
        for (unsigned i=0;i<len;++i) { data[i]=mem_cmd[i]; }
        if (event_on_read) { ++rf_events; event_on_read=false; }
    } else if (reg==0u) {
        CHECK(addr==0x57u && len==1u); data[0]=gpo_config;
    } else if (reg==0x2000u) {
        CHECK(addr==0x53u && len==1u); data[0]=gpo_dynamic;
    } else if (reg==0x2004u) {
        CHECK(addr==0x53u && len==1u); data[0]=security_session;
    } else if (reg==0x000du || reg==0x000eu) {
        CHECK(addr==0x57u && len==1u);data[0]=reg==0x000du?mb_mode:mb_watchdog;
    } else if (reg==0x2006u) {
        CHECK(addr==0x53u && len==1u);data[0]=mb_control;
    } else if (reg==0x2005u) {
        CHECK(addr==0x53u && len==1u);data[0]=mb_irq_status;mb_irq_status=0u;
    } else if (reg==0x2007u) {
        CHECK(addr==0x53u && len==1u && mb_length);data[0]=(uint8_t)(mb_length-1u);
    } else if (reg==0x2008u) {
        CHECK(addr==0x53u && len==mb_length);
        memcpy(data,mb_data,len);mb_control&=(uint8_t)~4u;
    } else {
        CHECK(reg==0x44u && addr==0x53u && len==4u);
        for (unsigned i=0;i<len;++i) { data[i]=ack_mem[i]; }
    }
    return 0;
}
int board_i2c_write(uint8_t addr,uint16_t reg,const uint8_t *data,size_t len)
{
    ++i2c_writes;
    if (bus_error) { return bus_error; }
    if (reg==0x0900u) {
        CHECK(addr==0x57u && len==17u && data[8]==0x09u);
        for (unsigned i=0;i<8u;++i) { CHECK(data[i]==data[i+9u]); }
        ++password_presents;
        security_session=(!wrong_password && data[0]==0u);
        return 0;
    }
    if (reg==0u) {
        CHECK(addr==0x57u && len==1u && security_session);
        CHECK(data[0]==0xc0u || data[0]==0xf0u); gpo_dynamic=gpo_config=data[0];
        ++config_writes;return 0;
    }
    if (reg==0x2000u) {
        CHECK(addr==0x53u && len==1u);
        gpo_dynamic=(gpo_config&0x7fu)|(data[0]&0x80u);
        return 0;
    }
    if (reg==0x000du || reg==0x000eu) {
        CHECK(addr==0x57u && len==1u && security_session);
        if (reg==0x000du) { mb_mode=data[0]; } else { mb_watchdog=data[0]; }
        return 0;
    }
    if (reg==0x2006u) {
        CHECK(addr==0x53u && len==1u);
        if (!mb_mode) { return -1; } /* observed K silicon: MB_MODE=0 */
        mb_control=(uint8_t)(data[0]&1u);
        return 0;
    }
    if (reg==0x2008u) {
        CHECK(addr==0x53u && len<=256u && len && (mb_control&1u) && !(mb_control&6u));
        memcpy(mb_data,data,len);mb_length=(uint16_t)len;mb_control|=2u;
        return 0;
    }
    CHECK(addr==0x53u && reg==0x44u && len==4u);
    ++ack_writes;
    for (unsigned i=0;i<len;++i) { ack_mem[i]=data[i]; }
    return 0;
}
static void reset_epd(unsigned mode,unsigned low)
{
    busy_mode=mode;polarity=low;old_phase=99u;ticks=0u;
    commands=0u;byte_count=0u;frame_count=0u;
}
static void protocol_test(void)
{
    command_tracker_t t={0};
    uint8_t p[4]={0xa5,1,1,0xa5};
    CHECK(command_valid(p));
    CHECK(!command_accept(&t,p)); /* baseline retained EEPROM */
    CHECK(!command_accept(&t,p)); /* duplicate */
    p[2]=2;p[3]=0xa6; CHECK(command_accept(&t,p));
    CHECK(!command_accept(&t,p));
    p[1]=2;CHECK(!command_accept(&t,p)); /* bad checksum */
    p[3]=0xa5;CHECK(!command_accept(&t,p)); /* same sequence */
    p[2]=255;p[3]=0x58;CHECK(command_accept(&t,p));
    p[2]=0;p[3]=0xa7;CHECK(command_accept(&t,p)); /* wrap */
    t=(command_tracker_t){0};p[0]=0;CHECK(!command_accept(&t,p));
    p[0]=0xa5;CHECK(command_accept(&t,p)); /* empty baseline then command */
    for (unsigned i=0;i<4u;++i) {
        p[i]^=1u;CHECK(!command_valid(p));p[i]^=1u;
    }
}
static void startup_test(void)
{
    for (unsigned stuck=0;stuck<2u;++stuck) {
        reset_epd(stuck,1u);bus_error=stuck ? -1:0;have_refreshed=false;uid_reads=0u;
        epd_hold_reset();CHECK(!panel_reset_high && epd_reset_held);
        panel_pin_writes=0u;
        g_debug=(app_debug_t){.stage=3u,.busy_active_low=1u};
        run_boot_sequence();
        CHECK(g_debug.boot_phase==4u && g_debug.refresh_count==0u);
        CHECK(g_debug.boot_colors_done_ms==0u && g_debug.boot_uid_start_ms==0u);
        CHECK(uid_reads==0u && board_millis()==0u && commands==0u);
        CHECK(frame_count==0u && panel_pin_writes==0u && !have_refreshed && !panel_reset_high);
    }
    bus_error=0;reset_epd(0u,1u);
    monitor_refresh=true;
    CHECK(execute(6u)==0u && g_debug.refresh_count==1u);
    uint32_t end=last_refresh_end;
    CHECK(execute(6u)==0u); /* only subsequent refreshes need the rest interval */
    CHECK((uint32_t)(board_millis()-end)>=5000u && g_debug.refresh_count==2u);
    monitor_refresh=false;
}
static void led_test(void)
{
    ticks=0u;status_led_set(LED_IDLE);
    CHECK(led_state[0] && !led_state[1]);
    board_delay_ms(100u);CHECK(!led_state[0] && !led_state[1]);
    board_delay_ms(1900u);CHECK(led_state[0] && !led_state[1]);
    status_led_set(LED_TRANSFER);
    board_delay_ms(100u);CHECK(!led_state[0] && !led_state[1]);
    board_delay_ms(100u);CHECK(led_state[0] && !led_state[1]);
    status_led_set(LED_REFRESH);
    board_delay_ms(35000u);CHECK(!led_state[0] && led_state[1]);
    status_led_set(LED_IDLE);ticks=40000u;status_led_error();
    CHECK(!led_state[0] && led_state[1]);
    board_delay_ms(250u);CHECK(!led_state[0] && !led_state[1]);
    board_delay_ms(500u);CHECK(!led_state[0] && !led_state[1]);
    board_delay_ms(2250u);CHECK(!led_state[1]); /* transient error expires */
    status_led_set(LED_ERROR);
    board_delay_ms(10000u);CHECK(led_state[1]); /* init error persists */
    status_led_set(LED_IDLE);
    ticks=0xfffffff0u;status_led_error();board_delay_ms(3000u);
    CHECK(!led_state[1]); /* wraparound does not latch an error forever */
}
static void indicator_recovery_test(void)
{
    uint32_t reported=0u;ticks=0u;
    g_debug.nfc_gpo_ready=1u;g_debug.nfc_pending=0u;
    image_transfer_init();g_image_debug.state=1u;
    g_image_debug.link_retries=3u;g_image_debug.link_error=-1;
    g_image_debug.errors=g_image_debug.crc_retries=1u;
    update_indicators(3u,&reported);
    CHECK(led_state[0] && !led_state[1] && reported==0u);
    g_image_debug.link_error=0;update_indicators(3u,&reported);
    CHECK(led_state[0] && !led_state[1]);
    ++g_image_debug.link_failures;update_indicators(3u,&reported);
    CHECK(!led_state[0] && led_state[1] && reported==1u);
    board_delay_ms(4000u);update_indicators(3u,&reported);
    CHECK(led_state[0] && !led_state[1]);
    ++g_image_debug.protocol_errors;update_indicators(3u,&reported);
    CHECK(!led_state[0] && led_state[1] && reported==2u);
    board_delay_ms(4000u);
    image_transfer_init();update_indicators(3u,&reported);
    CHECK(!led_state[1] && reported==0u); /* new session clears counters silently */
}
static void panel_test(void)
{
    uint8_t uid[8]={1,2,3,4,5,0x24,2,0xe0};
    reset_epd(0u,1u);CHECK(epd_show(1u,uid,1u)==0);
    CHECK(frame_count==28800u && epd_bytes_sent==28800u && epd_phase==0u);
    static const uint8_t colors[6]={0x00,0x11,0x22,0x33,0x55,0x66};
    for (unsigned y=0;y<240u;++y) {
        for (unsigned x=0;x<120u;++x) { CHECK(frame[y*120u+x]==colors[x/20u]); }
    }
    static const uint8_t expected[7]={0xe9,0x10,0x04,0x12,0x02,0x07,0};
    CHECK(commands==6u && datalog[0]==1u && datalog[5]==0xa5u);
    for (unsigned i=0;i<6u;++i) { CHECK(cmdlog[i]==expected[i]); }
    reset_epd(0u,0u);CHECK(epd_show(2u,uid,0u)==0); /* inverted BUSY */
    for (unsigned i=0;i<28800u;++i) { CHECK(frame[i]==0x11u); }
    reset_epd(1u,1u);CHECK(epd_show(1u,uid,1u)==-1); /* stuck busy */
    CHECK(commands==0u && frame_count==0u && ticks>=15000u);
    reset_epd(2u,1u);CHECK(epd_show(1u,uid,1u)==-9); /* never asserts */
    CHECK(commands==4u); /* no poweroff/sleep into unknown state */
    reset_epd(3u,1u);CHECK(epd_show(1u,uid,1u)==0);
    CHECK(epd_busy_glitches>=2u && epd_refresh_duration_ms>=1700u);
    CHECK(commands==6u); /* brief idle gaps must not trigger premature poweroff */
    reset_epd(4u,1u);CHECK(epd_show(1u,uid,1u)==-10);
    CHECK(commands==4u && epd_phase==5u); /* no poweroff/sleep on implausible completion */
    reset_epd(0u,1u);CHECK(epd_show(3u,uid,1u)==0);
    CHECK(frame_count==28800u);
    unsigned black=0u;
    for (unsigned i=0;i<28800u;++i) {
        uint8_t b=frame[i];
        CHECK((b>>4)<=6u && (b&15u)<=6u && (b>>4)!=4u && (b&15u)!=4u);
        if (i/120u>=50u && i/120u<128u && b!=0x11u) { ++black; }
    }
    CHECK(black>0u);
    reset_epd(0u,1u);CHECK(epd_show(6u,uid,1u)==0);
    unsigned digit_ink[4]={0};
    for (unsigned y=0;y<240u;++y) {
        for (unsigned x=0;x<240u;++x) {
            uint8_t b=frame[y*120u+x/2u];
            unsigned c=(x&1u)?(b&15u):(b>>4);
            CHECK(c==0u || c==1u);
            if (c==0u) {
                CHECK(x>=24u && x<216u && y>=92u && y<148u);
                if (x>=24u && x<216u) { ++digit_ink[(x-24u)/48u]; }
            }
        }
    }
    for (unsigned d=0;d<4u;++d) { CHECK(digit_ink[d]>0u); }
    reset_epd(0u,1u);CHECK(epd_show(4u,uid,1u)==0);
    CHECK(frame_count==28800u);
}
static void nfc_test(void)
{
    uint8_t uid[8],cmd[4];
    CHECK(st25_read_uid(uid)==0 && uid[7]==0xe0);
    mem_uid[5]=0x26;CHECK(st25_read_uid(uid)==-3); /* other capacity rejected */
    mem_uid[5]=0x24;
    mem_uid[7]=0;CHECK(st25_read_uid(uid)==-3);mem_uid[7]=0xe0;
    bus_error=-1;CHECK(st25_read_uid(uid)==-1);bus_error=0;
    CHECK(st25_read_command(cmd)==0 && cmd[0]==10u && cmd[3]==13u);
    CHECK(st25_write_ack(7u,4u)==0 && ack_writes==1u);
    CHECK(ack_mem[0]==0x5a && ack_mem[1]==7 && ack_mem[2]==4 && ack_mem[3]==0x59);
    bus_error=-2;CHECK(st25_write_ack(1u,0u)==-2);CHECK(ack_writes==1u);
    bus_error=0;
    CHECK(st25_configure_write_gpo()==0);
    CHECK(config_writes==1u && password_presents==2u && security_session==0u);
    CHECK(gpo_dynamic==0xc0u && gpo_config==0xc0u);
    CHECK(st25_configure_write_gpo()==0 && config_writes==1u); /* no EEPROM wear */
    gpo_config=0x88u;wrong_password=true;
    CHECK(st25_configure_write_gpo()==-6 && config_writes==1u);
    wrong_password=false;
    bus_error=-1;CHECK(st25_configure_write_gpo()==-1);bus_error=0;
}
static void make_command(unsigned seq)
{
    mem_cmd[0]=0xa5;mem_cmd[1]=6;mem_cmd[2]=(uint8_t)seq;
    mem_cmd[3]=(uint8_t)(0xa5u^6u^seq);
}
static void gpo_service_test(void)
{
    reset_epd(0u,1u);have_refreshed=false;bus_error=0;rf_events=0u;
    g_debug=(app_debug_t){.stage=3u,.busy_active_low=1u};
    nfc_state_t s;
    make_command(10u);unsigned initial_uid_reads=uid_reads;
    nfc_start(&s);
    CHECK(g_debug.nfc_gpo_ready==1u && g_debug.nfc_init_error==0);
    CHECK(uid_reads==initial_uid_reads+1u && s.tracker.seq==10u);
    CHECK(g_debug.nfc_boot_sequence==10u);
    unsigned reads=i2c_reads,writes=i2c_writes;
    for (unsigned i=0;i<1000u;++i) {
        board_delay_ms(10u);nfc_service(&s,ticks);
    }
    CHECK(i2c_reads==reads && i2c_writes==writes && g_debug.command_count==0u);
    CHECK(uid_reads==initial_uid_reads+1u); /* no periodic UID read */

    make_command(11u);++rf_events;
    nfc_service(&s,ticks);CHECK(s.pending && i2c_reads==reads);
    board_delay_ms(299u);nfc_service(&s,ticks);CHECK(i2c_reads==reads);
    board_delay_ms(1u);nfc_service(&s,ticks);
    CHECK(g_debug.command_count==1u && g_debug.last_sequence==11u);
    CHECK(g_debug.nfc_packet_result==3u && g_debug.nfc_last_packet[2]==11u);
    CHECK(g_debug.refresh_count==1u && g_debug.last_opcode==6u);
    CHECK(i2c_reads==reads+2u && s.ack_pending && !s.pending);
    board_delay_ms(500u);nfc_service(&s,ticks);
    CHECK(!s.ack_pending && g_debug.ack_error==0);

    /* A duplicate write event does not redraw or write another ACK. */
    unsigned refreshes=g_debug.refresh_count,acks=ack_writes;
    ++rf_events;nfc_service(&s,ticks);board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(g_debug.refresh_count==refreshes && ack_writes==acks);
    CHECK(g_debug.nfc_packet_result==2u);

    /* IRQ during either read defers dispatch until the new quiet interval. */
    make_command(12u);++rf_events;nfc_service(&s,ticks);
    event_on_read=true;board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(s.pending && g_debug.command_count==1u);
    nfc_service(&s,ticks);board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(g_debug.command_count==2u && g_debug.last_sequence==12u);
    board_delay_ms(500u);nfc_service(&s,ticks);

    /* RF arbitration may NACK: bounded retries, then completely quiet idle. */
    ++rf_events;nfc_service(&s,ticks);bus_error=-1;
    board_delay_ms(300u);nfc_service(&s,ticks);
    for (unsigned i=0;i<3u;++i) { board_delay_ms(150u);nfc_service(&s,ticks); }
    CHECK(!s.pending && s.attempts==4u && g_debug.nfc_read_errors==4u);
    reads=i2c_reads;writes=i2c_writes;
    for (unsigned i=0;i<1000u;++i) { board_delay_ms(10u);nfc_service(&s,ticks); }
    CHECK(i2c_reads==reads && i2c_writes==writes);
    bus_error=0;make_command(13u);++rf_events;nfc_service(&s,ticks);
    board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(g_debug.command_count==3u && g_debug.last_sequence==13u);
    board_delay_ms(500u);nfc_service(&s,ticks);

    /* IRQ accumulated during startup/display survives until service resumes. */
    ++rf_events;make_command(14u);board_delay_ms(30000u);
    nfc_service(&s,ticks);CHECK(s.pending);
    board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(g_debug.command_count==4u && g_debug.last_sequence==14u);

    /* A partially written packet must never trigger a refresh. */
    board_delay_ms(500u);nfc_service(&s,ticks);
    make_command(15u);mem_cmd[0]=0u;++rf_events;nfc_service(&s,ticks);
    board_delay_ms(300u);nfc_service(&s,ticks);
    CHECK(g_debug.command_count==4u && g_debug.nfc_packet_result==1u);
    CHECK(g_debug.nfc_last_packet[0]==0u && g_debug.nfc_last_packet[2]==15u);

    /* Failed initialization never silently falls back to continuous polling. */
    bus_error=-1;nfc_start(&s);CHECK(!g_debug.nfc_gpo_ready);
    CHECK(g_debug.nfc_init_error==-1);
    reads=i2c_reads;
    ++rf_events;board_delay_ms(10000u);nfc_service(&s,ticks);
    CHECK(i2c_reads==reads);bus_error=0;
}
#include "image_transfer_test.c"
#if __has_include("../NFC_APP/app/src/testInterop/android_app_interop.h")
#include "../NFC_APP/app/src/testInterop/android_app_interop.h"
#else
static void android_app_interop_test(void) {}
#endif
int test_entry(void)
{
    startup_test();led_test();indicator_recovery_test();protocol_test();nfc_test();gpo_service_test();
    image_protocol_test();image_hardware_interface_test();refresh_result_test();android_app_interop_test();panel_test();
    return (int)failures;
}
