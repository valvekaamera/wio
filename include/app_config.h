// Deployment defaults. Override any of these in include/wifi_secrets.h (gitignored)
// by defining the macro before this header is included.
#pragma once

#ifndef BACKEND_HOST
#define BACKEND_HOST "192.168.150.25"
#endif
#ifndef BACKEND_PORT
#define BACKEND_PORT 8090
#endif
#ifndef BACKEND_WS_PATH
#define BACKEND_WS_PATH "/ws/audio"
#endif
#ifndef DEVICE_NAME
#define DEVICE_NAME "wio-terminal"
#endif
#ifndef TRANSCRIPTION_LANGUAGE
#define TRANSCRIPTION_LANGUAGE "fi"
#endif
