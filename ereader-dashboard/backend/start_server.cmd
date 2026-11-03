@echo off
rem Starts the dashboard server on this laptop. Keep this window open (or minimise it).
cd /d "%~dp0"
py -3.10 dashboard_server.py serve %*
