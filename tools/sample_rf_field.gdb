# Usage: stop at an idle frame after board_init, set $rf_sample_count=80,
# enable GDB logging, then source tools/sample_rf_field.gdb.
# Reads only ST25DV04K EH_CTRL_Dyn; does not enable energy harvesting,
# configure GPO, write tag EEPROM, or flash firmware.
set $rf_saved_scratch = *(unsigned char*)0x20008800
set $rf_index = 0
set $rf_on_count = 0
set $rf_off_count = 0
set $rf_error_count = 0
set $rf_transitions = 0
set $rf_previous = -1
printf "RF_COLUMNS index tick_ms i2c_result eh_ctrl field_on vcc_on rcc_csr\n"
while $rf_index < $rf_sample_count
  set $rf_result = (int)board_i2c_read(0x53, 0x2002, (unsigned char*)0x20008800, 1)
  set $rf_tick = (unsigned int)board_millis()
  if $rf_result == 0
    set $rf_control = *(unsigned char*)0x20008800
    set $rf_present = (($rf_control & 4) != 0)
    set $rf_vcc = (($rf_control & 8) != 0)
    if $rf_present
      set $rf_on_count = $rf_on_count + 1
    else
      set $rf_off_count = $rf_off_count + 1
    end
    if $rf_previous >= 0 && $rf_present != $rf_previous
      set $rf_transitions = $rf_transitions + 1
    end
    set $rf_previous = $rf_present
    printf "RF_SAMPLE %u %u %d 0x%02x %u %u 0x%08x\n", $rf_index, $rf_tick, $rf_result, $rf_control, $rf_present, $rf_vcc, *(unsigned int*)0x40021060
  else
    set $rf_error_count = $rf_error_count + 1
    set $rf_previous = -1
    printf "RF_SAMPLE %u %u %d INVALID -1 -1 0x%08x\n", $rf_index, $rf_tick, $rf_result, *(unsigned int*)0x40021060
  end
  call (void)board_delay_ms(50)
  set $rf_index = $rf_index + 1
end
set {unsigned char}0x20008800 = $rf_saved_scratch
printf "RF_SUMMARY on=%u off=%u errors=%u transitions=%u\n", $rf_on_count, $rf_off_count, $rf_error_count, $rf_transitions
