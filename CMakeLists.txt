cmake_minimum_required(VERSION 3.20)
set(CMAKE_EXPORT_COMPILE_COMMANDS TRUE)
set(CMAKE_C_COMPILER_FORCED TRUE)
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/target.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/gcc-arm-none-eabi.cmake)
project(epaper_NFC LANGUAGES C ASM)

set(APP_BUSY_ACTIVE_LOW 1 CACHE STRING "Screen BUSY polarity: active LOW on this board")
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/VERSION" NFC_EPAPER_VERSION LIMIT_COUNT 1)
configure_file(Inc/version.h.in generated/version.h @ONLY)
configure_file(stm32g070xb_flash.ld stm32g070xb_flash.ld COPYONLY)

add_executable(${PROJECT_NAME}
    Src/main.c Src/board.c Src/epd.c Src/st25dv.c
    Src/image_transfer.c Src/image_link.c Src/status_led.c
    Src/startup_stm32g070xx.S Src/syscall.c Src/sysmem.c
)
target_include_directories(${PROJECT_NAME} PRIVATE
    Inc ${CMAKE_CURRENT_BINARY_DIR}/generated
    Drivers/CMSIS/Include Drivers/CMSIS/Device/ST/STM32G0xx/Include
)
target_compile_definitions(${PROJECT_NAME} PRIVATE STM32G070xx APP_BUSY_ACTIVE_LOW=${APP_BUSY_ACTIVE_LOW})
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/flags.cmake)
set_target_properties(${PROJECT_NAME} PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/stm32g070xb_flash.ld")
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_OBJCOPY} -O ihex $<TARGET_FILE:${PROJECT_NAME}> ${CMAKE_CURRENT_BINARY_DIR}/${PROJECT_NAME}.hex
    COMMAND ${CMAKE_OBJCOPY} -O binary $<TARGET_FILE:${PROJECT_NAME}> ${CMAKE_CURRENT_BINARY_DIR}/${PROJECT_NAME}.bin
    COMMAND ${CMAKE_SIZE} $<TARGET_FILE:${PROJECT_NAME}>
)
