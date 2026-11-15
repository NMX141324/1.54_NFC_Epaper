#include "st25dv.h"
#include "board.h"

static int present_password(uint8_t value)
{
    uint8_t packet[17];
    for (unsigned i=0;i<17u;++i) { packet[i]=value; }
    packet[8]=0x09u; /* Present, never Write Password (07). */
    return board_i2c_write(ST25_SYSTEM_ADDR7,0x0900u,packet,sizeof packet);
}
static int set_system_register(uint16_t reg,uint8_t value)
{
    uint8_t current=0u,session=0u;
    int r=board_i2c_read(ST25_SYSTEM_ADDR7,reg,&current,1u);
    if (r || current==value) { return r; }
    r=present_password(0u);
    if (!r) { r=board_i2c_read(ST25_USER_ADDR7,0x2004u,&session,1u); }
    if (!r && !(session&1u)) { r=-6; }
    if (!r) {
        r=board_i2c_write(ST25_SYSTEM_ADDR7,reg,&value,1u);
        board_delay_ms(10u);
    }
    int closed=present_password(0xffu);
    if (!r) { r=closed; }
    if (!r) { r=board_i2c_read(ST25_SYSTEM_ADDR7,reg,&current,1u); }
    return r ? r : (current==value?0:-7);
}
int st25_mailbox_control(uint8_t *value)
{ return board_i2c_read(ST25_USER_ADDR7,0x2006u,value,1u); }
int st25_mailbox_enable(bool enabled)
{
    uint8_t current=0u;
    int r=st25_mailbox_control(&current);
    if (r) { return r; }
    /* On the actual K silicon, an MB_EN write can NACK while MB_MODE=0,
     * even when requesting zero. Skip writes if already in the desired state. */
    if (((current&1u)!=0u)==enabled) { return 0; }
    uint8_t b=enabled?1u:0u;
    return board_i2c_write(ST25_USER_ADDR7,0x2006u,&b,1u);
}
int st25_configure_image_link(void)
{
    /* Disable mailbox while configuring the tag, then wait for the phone
     * to enable it and write a message. Only RF_PUT/RF_GET trigger GPO. */
    int r=st25_mailbox_enable(false);
    if (!r) { r=set_system_register(0x0000u,0xb0u); }
    if (!r) { r=set_system_register(0x000du,1u); }
    if (!r) { r=set_system_register(0x000eu,0u); }
    uint8_t b=0x80u;
    if (!r) { r=board_i2c_write(ST25_USER_ADDR7,0x2000u,&b,1u); }
    if (!r) { r=board_i2c_read(ST25_USER_ADDR7,0x2000u,&b,1u); }
    return r ? r : (b==0xb0u?0:-7);
}
int st25_mailbox_read(uint8_t data[256],uint16_t *length)
{
    uint8_t ctrl=0u,n=0u;
    int r=st25_mailbox_control(&ctrl);
    if (r) { return r; }
    if (!(ctrl&1u) || !(ctrl&4u)) { return -9; }
    r=board_i2c_read(ST25_USER_ADDR7,0x2007u,&n,1u);
    if (!r) {
        *length=(uint16_t)n+1u;
        r=board_i2c_read(ST25_USER_ADDR7,0x2008u,data,*length);
    }
    return r;
}
int st25_mailbox_write(const uint8_t *data,uint16_t length)
{
    if (!data || !length || length>256u) { return -2; }
    return board_i2c_write(ST25_USER_ADDR7,0x2008u,data,length);
}

int st25_read_uid(uint8_t uid[8])
{
    int r=board_i2c_read(ST25_SYSTEM_ADDR7,0x0018u,uid,8u);
    /* Actual board: ST25DV04K-IE, UID product byte and IC_REF = 0x24. */
    if (!r && (uid[7]!=0xe0u || uid[6]!=0x02u || uid[5]!=0x24u)) { return -3; }
    return r;
}
