#!/usr/bin/env bash
set -euo pipefail

g++ -std=c++17 -O2 -pthread chat_server.cpp -lssl -lcrypto -o chat_server
