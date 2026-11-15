#include "command.h"
bool command_valid(const uint8_t p[4])
{
    return p[0]==0xa5u && p[3]==(uint8_t)(p[0]^p[1]^p[2]);
}
bool command_accept(command_tracker_t *t, const uint8_t p[4])
{
    bool valid=command_valid(p);
    if (!t->baselined) {
        t->baselined=true;
        if (valid) { t->seq=p[2]; t->have_seq=true; }
        return false;
    }
    if (!valid || (t->have_seq && t->seq==p[2])) { return false; }
    t->seq=p[2]; t->have_seq=true;
    return true;
}
