#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int llama_server(int argc, char ** argv);

static void * watch_owner(void * value) {
    const pid_t expected = static_cast<pid_t>(reinterpret_cast<intptr_t>(value));
    while (getppid() == expected) usleep(250000);
    _exit(0);
}

int main(int argc, char ** argv) {
    const char * owner = getenv("HENYO_D1_OWNER_PID");
    const pid_t expected = owner ? static_cast<pid_t>(strtol(owner, nullptr, 10)) : getppid();
    if (expected <= 1 || getppid() != expected) {
        fputs("d1 worker owner is no longer available\n", stderr);
        return 1;
    }
    // PDEATHSIG follows the creating thread, which may be a short-lived Java
    // preparation thread. Watch the parent TGID instead of that thread's life.
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    pthread_t watcher;
    const int error = pthread_create(&watcher, &attributes, watch_owner,
                                    reinterpret_cast<void *>(static_cast<intptr_t>(expected)));
    pthread_attr_destroy(&attributes);
    if (error != 0) return 1;
    return llama_server(argc, argv);
}
