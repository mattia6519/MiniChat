@echo off
g++ -std=c++17 -O2 chat_server.cpp -lws2_32 -lwininet -lcrypt32 -o chat_server.exe
if errorlevel 1 exit /b %errorlevel%
echo Creato chat_server.exe
