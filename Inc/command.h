#ifndef COMMAND_H
#define COMMAND_H
#include <stdbool.h>
#include <stdint.h>
typedef struct { bool baselined; bool have_seq; uint8_t seq; } command_tracker_t;
/* Existing EEPROM contents are baselined on entering stage 3, not replayed. */
bool command_accept(command_tracker_t *tracker, const uint8_t packet[4]);
bool command_valid(const uint8_t packet[4]);
#endif
