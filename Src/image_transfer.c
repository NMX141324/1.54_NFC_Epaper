/* ST25FTM wire format: ST UM2062 appendix A / AN5512.
 * Application command 04 is used as a FILE transport only: no Flash writes,
 * option-byte changes, firmware execution, or JPEG decoding occur here. */
#include "image_transfer.h"
#include <string.h>

volatile image_debug_t g_image_debug;
static uint8_t image_file[IMAGE_FILE_BYTES];
static uint8_t small[64],response[32];
static struct {
    uint32_t received,total,committed,segment_len,segment_start,last_tick;
    uint32_t crc_be,crc_ieee,crc_le,word,word_bytes;
    uint32_t tx_length,tx_offset,tx_chunk;
    uint8_t cmd,expected_id,ack,tx_kind,profile;
    bool rx,segment,duplicate,duplicate_after_done,finish,ack_pending,inflight,tx,readback;
    bool password_ok,valid,refresh_after_response,refresh_ready,wait_refresh;
} f;

static uint32_t le32(const uint8_t *p)
{ return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }
static uint32_t be32(const uint8_t *p)
{ return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }
static uint32_t crc_be_byte(uint32_t c,uint8_t b)
{
    c^=(uint32_t)b<<24;
    for (unsigned i=0;i<8u;++i) { c=(c<<1)^((c&0x80000000u)?0x04c11db7u:0u); }
    return c;
}
static uint32_t crc_ieee_byte(uint32_t c,uint8_t b)
{
    c^=b;
    for (unsigned i=0;i<8u;++i) { c=(c>>1)^((c&1u)?0xedb88320u:0u); }
    return c;
}
uint32_t image_crc32(const uint8_t *p,uint32_t n)
{
    uint32_t c=0xffffffffu;
    while (n--) { c=crc_ieee_byte(c,*p++); }
    return c^0xffffffffu;
}
int image_file_validate(const uint8_t *p,uint32_t n)
{
    if (!p || n!=IMAGE_FILE_BYTES || memcmp(p,"E6IM",4u) || p[4]!=1u || p[5]!=1u ||
        p[6]!=32u || p[7] || p[8]!=240u || p[9] || p[10]!=240u || p[11] ||
        le32(p+12)!=IMAGE_PIXEL_BYTES || le32(p+24)) { return -20; }
    if (image_crc32(p,28u)!=le32(p+28) ||
        image_crc32(p+32u,IMAGE_PIXEL_BYTES)!=le32(p+20)) { return -21; }
    for (uint32_t i=32u;i<n;++i) {
        unsigned a=p[i]>>4,b=p[i]&15u;
        if (a>6u || a==4u || b>6u || b==4u) { return -22; }
    }
    return 0;
}
static void crc_start(void)
{ f.crc_be=f.crc_ieee=f.crc_le=0xffffffffu; f.word=0u; f.word_bytes=0u; }
static void crc_add(const uint8_t *p,uint32_t n)
{
    while (n--) {
        uint8_t b=*p++;
        f.crc_be=crc_be_byte(f.crc_be,b); f.crc_ieee=crc_ieee_byte(f.crc_ieee,b);
        f.word|=(uint32_t)b<<(8u*f.word_bytes++);
        if (f.word_bytes==4u) {
            for (unsigned i=0;i<4u;++i) { f.crc_le=crc_be_byte(f.crc_le,(uint8_t)(f.word>>(24u-8u*i))); }
            f.word=0u; f.word_bytes=0u;
        }
    }
}
static bool crc_matches(uint32_t peer)
{
    /* ST's older ports pad to a word; newer ports can feed bytes directly.
     * Lock the detected 32-bit CRC profile for the rest of a command. */
    uint32_t values[4]={f.crc_be,f.crc_be,f.crc_le,f.crc_ieee^0xffffffffu};
    if (f.word_bytes) {
        for (unsigned i=f.word_bytes;i<4u;++i) { values[1]=crc_be_byte(values[1],0u); }
        for (unsigned i=0;i<4u;++i) { values[2]=crc_be_byte(values[2],(uint8_t)(f.word>>(24u-8u*i))); }
    }
    if (f.profile) { return values[f.profile-1u]==peer; }
    for (unsigned i=0;i<4u;++i) {
        if (values[i]==peer) { f.profile=(uint8_t)(i+1u);g_image_debug.crc_profile=f.profile;return true; }
    }
    return false;
}
void image_transfer_init(void)
{
    memset(&f,0,sizeof f);memset((void*)&g_image_debug,0,sizeof g_image_debug);
}
static void reply(uint8_t status,uint32_t extra)
{
    response[0]=(uint8_t)(f.cmd|0x80u);response[1]=status;
    f.tx=true;f.readback=false;f.tx_offset=0u;f.tx_length=2u+extra;
    g_image_debug.last_status=status;
}
static void command_complete(void)
{
    f.rx=false;g_image_debug.state=2u;g_image_debug.command=f.cmd;
    uint32_t data_length=f.total-1u;
    if (f.cmd==0u) {
        reply(0x81u,4u);response[2]=0xffu;response[3]=5u;
        response[4]=1u;response[5]=0u; /* capability bit0: command 09 completion */
    } else if (f.cmd==7u) {
        f.password_ok=(data_length==4u &&
            (be32(small)==0x12345678u || le32(small)==0x12345678u)) ||
            (data_length==8u && !memcmp(small,"12345678",8u));
        reply(f.password_ok?0x81u:0x85u,0u);
    } else if (f.cmd==4u || f.cmd==5u || f.cmd==1u || f.cmd==9u) {
        int r=(f.cmd==4u && !f.password_ok) ? -23 : image_file_validate(image_file,data_length);
        if (!r) {
            f.valid=true;f.refresh_after_response=true;
            g_image_debug.image_id=le32(image_file+16);g_image_debug.image_crc=le32(image_file+20);
            g_image_debug.image_valid=1u;reply(0x81u,0u);
            f.wait_refresh=f.cmd==9u;
            g_image_debug.refresh_state=1u; /* received, not yet displayed */
        } else {
            ++g_image_debug.errors;++g_image_debug.protocol_errors;
            g_image_debug.link_error=r;reply(0x82u,0u);
        }
    } else if (f.cmd==6u || f.cmd==2u) {
        if (f.valid) {
            reply(0x81u,0u);f.readback=true;f.tx_length=2u+IMAGE_FILE_BYTES;
        } else { reply(0x85u,0u); }
    } else { reply(0x84u,0u); }
}
static void store_payload(const uint8_t *p,uint32_t n)
{
    for (uint32_t i=0;i<n;++i) {
        uint32_t offset=f.received+i;
        if (!offset) {
            f.cmd=p[i];g_image_debug.command=f.cmd;
            if (f.cmd==1u || f.cmd==4u || f.cmd==5u || f.cmd==9u) {
                f.valid=false;f.refresh_ready=false;f.refresh_after_response=false;
                f.wait_refresh=false;g_image_debug.refresh_state=0u;
                g_image_debug.image_valid=0u;g_image_debug.refresh_ready=0u;
            }
        } else if (f.cmd==1u || f.cmd==4u || f.cmd==5u || f.cmd==9u) {
            image_file[offset-1u]=p[i];
        } else if (offset<=sizeof small) { small[offset-1u]=p[i]; }
    }
}
static int packet_error(void)
{
    ++g_image_debug.errors;++g_image_debug.protocol_errors;
    f.ack=0x83u;f.ack_pending=true;f.rx=false;
    return -24;
}
int image_transfer_receive(const uint8_t *p,uint32_t n,uint32_t now)
{
    if (!p || !n || n>256u || f.inflight || f.ack_pending) { return -25; }
    uint8_t c=p[0],pos=(c>>2)&3u,ac=(c>>4)&3u,id=(c>>1)&1u;
    bool segmented=(c&1u)!=0u,first=(pos==0u || pos==1u),last=(pos==0u || pos==3u);
    uint32_t h=1u,bytes,total=0u;
    g_image_debug.last_control=c;++g_image_debug.packets;f.last_tick=now;
    if (c&0x80u) { return packet_error(); }
    if (f.tx) {
        if (first) {
            /* Sender restarted/abandoned its old command before accepting
             * the response. Start afresh; a partial image is never displayed. */
            f.tx=false;
            f.refresh_after_response=false;
        } else if (!f.rx && segmented && (ac&1u) && id!=f.expected_id) {
            /* Final ACK lost: acknowledge the old final segment again while
             * preserving the command response waiting behind that ACK. */
            f.rx=true;f.duplicate_after_done=true;
        } else if (!f.duplicate_after_done) { return -25; }
    }
    if (c&0x40u) { if (n<2u) { return packet_error(); } bytes=p[h++]; }
    else { bytes=0u; }
    if (pos==1u) { if (n<h+4u) { return packet_error(); } total=be32(p+h);h+=4u; }
    if (!(c&0x40u)) { bytes=n-h; }
    if (bytes>n-h || ((ac&2u) && bytes<4u)) { return packet_error(); }
    uint32_t data_bytes=bytes-((ac&2u)?4u:0u);
    if (first) {
        if (pos==0u) { total=data_bytes; }
        if (!total || total>IMAGE_FILE_BYTES+1u) { return packet_error(); }
        /* A FIRST/SINGLE packet starts a new command, including retransmission
         * of its first segment. Never retain bytes from an abandoned command. */
        f.rx=true;f.received=0u;f.committed=0u;f.total=total;
        f.expected_id=0u;f.segment=false;f.duplicate=false;f.segment_len=0u;f.profile=0u;
        f.duplicate_after_done=false;
        g_image_debug.crc_profile=0u;
        g_image_debug.total=total;g_image_debug.received=0u;g_image_debug.state=1u;
    } else if (!f.rx) { return packet_error(); }
    if (segmented && (ac&1u)) {
        if (f.segment_len) { ++g_image_debug.retries; }
        f.received=f.committed;f.segment_start=f.committed;f.segment_len=0u;
        f.segment=true;f.duplicate=id!=f.expected_id;
        if (f.duplicate) { ++g_image_debug.retries; }
        crc_start();
    } else if (segmented && (!f.segment || id!=(f.duplicate?(f.expected_id^1u):f.expected_id))) {
        return packet_error();
    }
    if ((!f.duplicate && (f.received>f.total || data_bytes>f.total-f.received ||
         data_bytes>IMAGE_FILE_BYTES+1u-f.received)) ||
        (f.duplicate && data_bytes>IMAGE_FILE_BYTES+1u-f.segment_len)) { return packet_error(); }
    if (!f.duplicate) { store_payload(p+h,data_bytes); }
    f.received+=data_bytes;f.segment_len+=data_bytes;
    if (segmented) { crc_add(p+h,data_bytes); }
    f.finish=last;
    if (ac&2u) {
        if (!segmented) { return packet_error(); }
        uint32_t crc=be32(p+h+data_bytes);g_image_debug.peer_crc=crc;
        bool ok=crc_matches(crc);
        f.ack=ok?0x80u:0x81u;f.ack_pending=true;
        if (!ok) { ++g_image_debug.errors;++g_image_debug.retries;++g_image_debug.crc_retries; }
        if (last && !f.duplicate && f.received!=f.total) { f.ack=0x81u; }
    } else if (!segmented) {
        f.committed=f.received;g_image_debug.received=f.committed;
        if (last) {
            if (f.received!=f.total) { return packet_error(); }
            command_complete();
        }
    } else if (last) { return packet_error(); }
    return 0;
}
static uint8_t tx_byte(uint32_t offset)
{
    if (f.readback) {
        if (offset==0u) { return (uint8_t)(f.cmd|0x80u); }
        if (offset==1u) { return 0x81u; }
        return image_file[offset-2u];
    }
    return response[offset];
}
bool image_transfer_next_tx(uint8_t p[256],uint16_t *n)
{
    if (f.inflight) { return false; }
    if (f.ack_pending) { p[0]=f.ack;*n=1u;f.tx_kind=1u;return true; }
    if (!f.tx) { return false; }
    uint32_t remaining=f.tx_length-f.tx_offset,amount=remaining>240u?240u:remaining;
    uint32_t h=2u;
    uint8_t pos=f.tx_length<=240u?0u:(!f.tx_offset?1u:(remaining<=240u?3u:2u));
    p[0]=(uint8_t)(0x40u|(pos<<2));p[1]=(uint8_t)amount;
    if (pos==1u) {
        p[2]=(uint8_t)(f.tx_length>>24);p[3]=(uint8_t)(f.tx_length>>16);
        p[4]=(uint8_t)(f.tx_length>>8);p[5]=(uint8_t)f.tx_length;h+=4u;
    }
    for (uint32_t i=0;i<amount;++i) { p[h+i]=tx_byte(f.tx_offset+i); }
    *n=(uint16_t)(h+amount);f.tx_chunk=amount;f.tx_kind=2u;return true;
}
void image_transfer_sent(uint32_t now) { f.inflight=true;f.last_tick=now; }
bool image_transfer_inflight(void) { return f.inflight; }
void image_transfer_released(uint32_t now)
{
    if (!f.inflight) { return; }
    f.inflight=false;f.last_tick=now;
    if (f.tx_kind==1u) {
        f.ack_pending=false;
        if (f.ack==0x80u) {
            if (!f.duplicate) {
                f.committed=f.received;f.expected_id^=1u;++g_image_debug.segments;
                g_image_debug.received=f.committed;
            } else { f.received=f.committed; }
            f.segment_len=0u;f.segment=false;
            if (f.duplicate_after_done) { f.rx=false;f.duplicate_after_done=false; }
            else if (f.finish && !f.duplicate) { command_complete(); }
        } else { f.received=f.committed;f.segment_len=0u;f.segment=false; }
    } else {
        f.tx_offset+=f.tx_chunk;g_image_debug.tx_bytes+=f.tx_chunk;
        if (f.tx_offset==f.tx_length) {
            f.tx=false;g_image_debug.state=0u;
            if (f.refresh_after_response && f.valid) {
                f.refresh_ready=true;f.refresh_after_response=false;
                g_image_debug.refresh_ready=1u;
            }
        }
    }
}
bool image_transfer_tick(uint32_t now)
{
    if ((f.rx || f.tx || f.inflight || f.ack_pending) && (uint32_t)(now-f.last_tick)>15000u) {
        f.rx=f.tx=f.inflight=f.ack_pending=f.segment=false;
        f.refresh_after_response=false;f.refresh_ready=false;
        g_image_debug.refresh_ready=0u;g_image_debug.state=0u;++g_image_debug.timeouts;
        return true;
    }
    return false;
}
void image_transfer_link_closed(void)
{
    f.rx=f.tx=f.inflight=f.ack_pending=f.segment=false;
    f.duplicate_after_done=false;f.refresh_after_response=false;
    g_image_debug.state=0u;
    if (f.wait_refresh) {
        /* Command 09 explicitly keeps the phone/session through refresh.
         * If it closed before dispatch, do not start a new panel job. */
        f.wait_refresh=false;f.refresh_ready=false;
        g_image_debug.refresh_ready=0u;
        g_image_debug.refresh_state=4u;g_image_debug.refresh_error=-26;
    }
    /* Keep refresh_ready only if the final command response was actually
     * consumed by RF for legacy commands. Closing a command09 session never
     * starts a new refresh. */
}
const uint8_t *image_transfer_take_frame(void)
{
    if (!f.valid || !f.refresh_ready) { return 0; }
    f.refresh_ready=false;g_image_debug.refresh_ready=0u;
    g_image_debug.refresh_state=2u;
    return image_file+IMAGE_HEADER_BYTES;
}
bool image_transfer_refresh_finished(int error,uint32_t elapsed_ms,uint32_t reset_flags,uint32_t now)
{
    g_image_debug.refresh_error=error;g_image_debug.refresh_elapsed_ms=elapsed_ms;
    g_image_debug.refresh_state=error?4u:3u;
    if (!f.wait_refresh || !f.valid) { return false; }
    f.wait_refresh=false;
    reply(error?0x91u:0x90u,20u);
    uint32_t fields[5]={g_image_debug.image_id,g_image_debug.image_crc,elapsed_ms,
                        (uint32_t)error,reset_flags};
    for (unsigned i=0;i<5u;++i) {
        for (unsigned b=0;b<4u;++b) { response[2u+i*4u+b]=(uint8_t)(fields[i]>>(8u*b)); }
    }
    f.last_tick=now;
    g_image_debug.state=2u;
    return true;
}
