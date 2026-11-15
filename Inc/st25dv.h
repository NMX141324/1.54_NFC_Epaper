#ifndef ST25DV_H
#define ST25DV_H
#include <stdint.h>
#include <stdbool.h>
/* ST25DV04K-IE on the actual board, 512-byte user EEPROM. */
#define ST25_USER_ADDR7 0x53u
#define ST25_SYSTEM_ADDR7 0x57u
#define ST25_CMD_OFFSET 0x0040u
#define ST25_ACK_OFFSET 0x0044u
int st25_read_uid(uint8_t uid[8]);
int st25_read_command(uint8_t cmd[4]);
int st25_write_ack(uint8_t seq, uint8_t status);
/* Enable only RF EEPROM write-complete pulses. Uses factory I2C password
 * to authorize GPO configuration; never changes passwords or lock bits. */
int st25_configure_write_gpo(void);
int st25_configure_image_link(void);
int st25_mailbox_control(uint8_t *value);
int st25_mailbox_enable(bool enabled);
int st25_mailbox_read(uint8_t data[256],uint16_t *length);
int st25_mailbox_write(const uint8_t *data,uint16_t length);
#endif
