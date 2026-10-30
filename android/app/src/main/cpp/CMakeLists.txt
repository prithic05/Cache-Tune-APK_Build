cmake_minimum_required(VERSION 3.22.1)

project("cachetune_decryptor")

add_library(
    cachetune_decryptor
    SHARED
    decryptor_bridge.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    cachetune_decryptor
    ${log-lib}
)
