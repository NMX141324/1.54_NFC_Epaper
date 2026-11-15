#include "image_transfer.h"
#include "board.h"
#include "st25dv.h"

static struct {
    uint32_t events,last_attempt;unsigned tries;
    uint8_t irq_status;bool pending,mailbox,irq_pending;
} link;
void image_link_init(void)
{
    link.events=board_nfc_write_events();link.last_attempt=0u;
    link.tries=0u;link.pending=false;link.mailbox=false;
    link.irq_status=0u;link.irq_pending=false;
    image_transfer_init();
}
bool image_link_service(uint32_t now)
{
    uint32_t events=board_nfc_write_events();
    if (events!=link.events) {
        link.events=events;link.pending=true;link.tries=0u;
        link.irq_pending=true;
        link.last_attempt=now-5u;
    }
    if (image_transfer_tick(now)) {
        int r=st25_mailbox_enable(false);
        g_image_debug.link_error=r;link.pending=false;link.mailbox=false;
    }
    if (!link.pending || (uint32_t)(now-link.last_attempt)<5u) { return link.mailbox; }
    link.last_attempt=now;++link.tries;
    uint8_t control=0u;
    int r=0;
    if (link.irq_pending) {
        uint8_t bits=0u;
        r=board_i2c_read(ST25_USER_ADDR7,0x2005u,&bits,1u);
        if (!r) { link.irq_status|=bits;link.irq_pending=false; }
    }
    if (!r) { r=st25_mailbox_control(&control); }
    g_image_debug.mailbox_control=control;
    if (!r) {
        link.mailbox=(control&1u)!=0u;
        if (!link.mailbox) {
            if (image_transfer_inflight() && (link.irq_status&0x40u)) {
                image_transfer_released(now);
            }
            image_transfer_link_closed();
            link.pending=false;link.tries=0u;link.irq_status=0u;return false;
        }
        if (!(control&2u) && image_transfer_inflight()) { image_transfer_released(now); }
        if (control&4u) {
            uint8_t message[256];uint16_t length=0u;
            r=st25_mailbox_read(message,&length);
            if (!r) { (void)image_transfer_receive(message,length,now); }
        }
        if (!r && !(control&2u)) {
            uint8_t message[256];uint16_t length=0u;
            if (image_transfer_next_tx(message,&length)) {
                r=st25_mailbox_write(message,length);
                if (!r) { image_transfer_sent(now); }
            }
        }
    }
    g_image_debug.link_error=r;
    if (!r) { link.pending=false;link.tries=0u;link.irq_status=0u; }
    else if (link.tries>=20u) { link.pending=false; }
    return link.mailbox;
}
