# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.16)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
file(STRINGS "${CMAKE_CURRENT_LIST_DIR}/../version.properties" FOCUSMATE_VERSION_LINE REGEX "^VERSION_NAME=")
string(REPLACE "VERSION_NAME=" "" PROJECT_VER "${FOCUSMATE_VERSION_LINE}")
if(PROJECT_VER STREQUAL "")
    message(FATAL_ERROR "VERSION_NAME is missing from version.properties")
endif()
project(focusmate_esp)
