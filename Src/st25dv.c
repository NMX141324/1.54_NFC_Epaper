#include "st25dv.h"
#include "board.h"

static int present_password(uint8_t value)
{
    uint8_t packet[17];
    for (unsigned i=0;i<17u;++i) { packet[i]=value; }
    packet[8]=0x09u; /* Present, never Write Password (07). */
    return board_i2c_write(ST25_SYSTEM_ADDR7,0x0900u,packet,sizeof packet);
}
int st25_configure_write_gpo(void)
{
    uint8_t config=0u,session=0u;
    int r=board_i2c_read(ST25_SYSTEM_ADDR7,0x0000u,&config,1u);
    if (r) { return r; }
    if (config!=0xc0u) {
        r=present_password(0u); /* Factory password: 64 zero bits. */
        if (!r) { r=board_i2c_read(ST25_USER_ADDR7,0x2004u,&session,1u); }
        if (!r && !(session&1u)) { r=-6; }
        if (!r) {
            config=0xc0u; /* GPO_EN | RF_WRITE_EN; no field/activity pulses. */
            r=board_i2c_write(ST25_SYSTEM_ADDR7,0x0000u,&config,1u);
            board_delay_ms(10u);
        }
        /* Close the session with a deliberately different presentation.
         * The stored password was proven to be zero; it is never modified. */
        int close_result=present_password(0xffu);
        if (!r) { r=close_result; }
        if (r) { return r; }
        r=board_i2c_read(ST25_SYSTEM_ADDR7,0x0000u,&config,1u);
        if (r) { return r; }
        if (config!=0xc0u) { return -7; }
    }
    config=0x80u;
    r=board_i2c_write(ST25_USER_ADDR7,0x2000u,&config,1u);
    if (!r) { r=board_i2c_read(ST25_USER_ADDR7,0x2000u,&config,1u); }
    return r ? r : (config==0xc0u ? 0 : -7);
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
    /* EEPROM and mailbox share the internal buffer. Disable mailbox before
     * changing EEPROM, then leave it OFF for legacy commands. The ST App
     * enables mailbox on entering FTM; the first GPO event selects that mode. */
    int r=st25_mailbox_enable(false);
    if (!r) { r=set_system_register(0x0000u,0xf0u); }
    if (!r) { r=set_system_register(0x000du,1u); }
    if (!r) { r=set_system_register(0x000eu,0u); }
    uint8_t b=0x80u;
    if (!r) { r=board_i2c_write(ST25_USER_ADDR7,0x2000u,&b,1u); }
    if (!r) { r=board_i2c_read(ST25_USER_ADDR7,0x2000u,&b,1u); }
    return r ? r : (b==0xf0u?0:-7);
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
int st25_read_command(uint8_t cmd[4])
{
    return board_i2c_read(ST25_USER_ADDR7,ST25_CMD_OFFSET,cmd,4u);
}
int st25_write_ack(uint8_t seq, uint8_t status)
{
    uint8_t b[4]={0x5au,seq,status,(uint8_t)(0x5au^seq^status)};
    int r=board_i2c_write(ST25_USER_ADDR7,ST25_ACK_OFFSET,b,4u);
    if (r) { return r; }
    /* EEPROM tW: permit RF arbitration and verify persistent result. */
    uint8_t check[4];
    uint32_t start=board_millis();
    do {
        board_delay_ms(5u);
        r=board_i2c_read(ST25_USER_ADDR7,ST25_ACK_OFFSET,check,4u);
        if (!r) {
            for (unsigned i=0;i<4u;++i) { if (check[i]!=b[i]) { return -4; } }
            return 0;
        }
    } while ((uint32_t)(board_millis()-start)<50u);
    return r;
}
