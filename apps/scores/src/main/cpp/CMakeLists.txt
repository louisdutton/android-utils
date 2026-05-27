cmake_minimum_required(VERSION 3.22.1)

project(scores_verovio_bridge)

add_library(scores_verovio_bridge SHARED verovio_bridge.cpp)

find_library(log-lib log)

target_link_libraries(scores_verovio_bridge ${log-lib})
