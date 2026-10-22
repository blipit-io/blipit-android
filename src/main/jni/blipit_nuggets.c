#include <jni.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#define MAX_FRAMES 64

static char report_path[1024];
static struct sigaction previous[NSIG];
static const int watched[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
static volatile sig_atomic_t handling = 0;
static int installed = 0;

static void put(int fd, const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    if (write(fd, s, n) < 0) return;
}

static void put_hex(int fd, uintptr_t v) {
    char buf[2 + sizeof(uintptr_t) * 2 + 1];
    int digits = sizeof(uintptr_t) * 2;
    buf[0] = '0';
    buf[1] = 'x';
    for (int i = 0; i < digits; i++) {
        int d = (int) ((v >> ((digits - 1 - i) * 4)) & 0xf);
        buf[2 + i] = (char) (d < 10 ? '0' + d : 'a' + d - 10);
    }
    buf[2 + digits] = 0;
    put(fd, buf);
}

static void put_dec(int fd, long v) {
    char buf[24];
    int i = 23;
    int negative = v < 0;
    unsigned long u = negative ? (unsigned long) (-(v + 1)) + 1 : (unsigned long) v;
    buf[i] = 0;
    do {
        buf[--i] = (char) ('0' + u % 10);
        u /= 10;
    } while (u && i > 1);
    if (negative) buf[--i] = '-';
    put(fd, buf + i);
}

static void line_dec(int fd, const char *key, long v) {
    put(fd, key);
    put(fd, " ");
    put_dec(fd, v);
    put(fd, "\n");
}

static void line_hex(int fd, const char *key, uintptr_t v) {
    put(fd, key);
    put(fd, " ");
    put_hex(fd, v);
    put(fd, "\n");
}

static void reg(int fd, const char *name, uintptr_t v) {
    put(fd, "reg ");
    put(fd, name);
    put(fd, " ");
    put_hex(fd, v);
    put(fd, "\n");
}

static uintptr_t registers(int fd, ucontext_t *uc) {
#if defined(__aarch64__)
    static const char *names[] = {"x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15",
                                  "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23", "x24", "x25", "x26", "x27", "x28", "fp", "lr"};
    for (int i = 0; i < 31; i++) reg(fd, names[i], (uintptr_t) uc->uc_mcontext.regs[i]);
    reg(fd, "sp", (uintptr_t) uc->uc_mcontext.sp);
    reg(fd, "pc", (uintptr_t) uc->uc_mcontext.pc);
    return (uintptr_t) uc->uc_mcontext.pc;
#elif defined(__arm__)
    const struct sigcontext *c = &uc->uc_mcontext;
    reg(fd, "r0", c->arm_r0);
    reg(fd, "r1", c->arm_r1);
    reg(fd, "r2", c->arm_r2);
    reg(fd, "r3", c->arm_r3);
    reg(fd, "r4", c->arm_r4);
    reg(fd, "r5", c->arm_r5);
    reg(fd, "r6", c->arm_r6);
    reg(fd, "r7", c->arm_r7);
    reg(fd, "r8", c->arm_r8);
    reg(fd, "r9", c->arm_r9);
    reg(fd, "r10", c->arm_r10);
    reg(fd, "fp", c->arm_fp);
    reg(fd, "ip", c->arm_ip);
    reg(fd, "sp", c->arm_sp);
    reg(fd, "lr", c->arm_lr);
    reg(fd, "pc", c->arm_pc);
    return (uintptr_t) c->arm_pc;
#elif defined(__x86_64__)
    const greg_t *g = uc->uc_mcontext.gregs;
    reg(fd, "rax", (uintptr_t) g[REG_RAX]);
    reg(fd, "rbx", (uintptr_t) g[REG_RBX]);
    reg(fd, "rcx", (uintptr_t) g[REG_RCX]);
    reg(fd, "rdx", (uintptr_t) g[REG_RDX]);
    reg(fd, "rsi", (uintptr_t) g[REG_RSI]);
    reg(fd, "rdi", (uintptr_t) g[REG_RDI]);
    reg(fd, "rbp", (uintptr_t) g[REG_RBP]);
    reg(fd, "rsp", (uintptr_t) g[REG_RSP]);
    reg(fd, "rip", (uintptr_t) g[REG_RIP]);
    return (uintptr_t) g[REG_RIP];
#elif defined(__i386__)
    const greg_t *g = uc->uc_mcontext.gregs;
    reg(fd, "eax", (uintptr_t) g[REG_EAX]);
    reg(fd, "ebx", (uintptr_t) g[REG_EBX]);
    reg(fd, "ecx", (uintptr_t) g[REG_ECX]);
    reg(fd, "edx", (uintptr_t) g[REG_EDX]);
    reg(fd, "esi", (uintptr_t) g[REG_ESI]);
    reg(fd, "edi", (uintptr_t) g[REG_EDI]);
    reg(fd, "ebp", (uintptr_t) g[REG_EBP]);
    reg(fd, "esp", (uintptr_t) g[REG_ESP]);
    reg(fd, "eip", (uintptr_t) g[REG_EIP]);
    return (uintptr_t) g[REG_EIP];
#else
    (void) fd;
    (void) uc;
    return 0;
#endif
}

struct walk {
    uintptr_t frames[MAX_FRAMES];
    int count;
};

static _Unwind_Reason_Code step(struct _Unwind_Context *ctx, void *arg) {
    struct walk *w = (struct walk *) arg;
    uintptr_t pc = (uintptr_t) _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_NO_REASON;
    if (w->count >= MAX_FRAMES) return _URC_END_OF_STACK;
    w->frames[w->count++] = pc;
    return _URC_NO_REASON;
}

static void copy(int out, const char *path) {
    char buf[4096];
    int in = open(path, O_RDONLY);
    if (in < 0) return;
    ssize_t n;
    while ((n = read(in, buf, sizeof buf)) > 0) {
        if (write(out, buf, (size_t) n) < 0) break;
    }
    close(in);
}

static void thread_name(int fd) {
    char buf[64];
    int in = open("/proc/thread-self/comm", O_RDONLY);
    if (in < 0) return;
    ssize_t n = read(in, buf, sizeof buf - 1);
    close(in);
    if (n <= 0) return;
    buf[n] = 0;
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == '\n') buf[i] = 0;
    }
    put(fd, "thread ");
    put(fd, buf);
    put(fd, "\n");
}

static void handler(int sig, siginfo_t *info, void *context) {
    if (!handling) {
        handling = 1;
        int fd = open(report_path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
        if (fd >= 0) {
            line_dec(fd, "signal", sig);
            line_dec(fd, "code", info ? info->si_code : 0);
            line_hex(fd, "addr", info ? (uintptr_t) info->si_addr : 0);
            line_dec(fd, "tid", (long) syscall(SYS_gettid));
            thread_name(fd);
            uintptr_t pc = context ? registers(fd, (ucontext_t *) context) : 0;
            line_hex(fd, "pc", pc);
            if (pc) line_hex(fd, "frame", pc);
            struct walk w;
            w.count = 0;
            _Unwind_Backtrace(step, &w);
            for (int i = 0; i < w.count; i++) line_hex(fd, "frame", w.frames[i]);
            put(fd, "maps\n");
            copy(fd, "/proc/self/maps");
            close(fd);
        }
    }
    sigaction(sig, &previous[sig], NULL);
    if (!info || info->si_code <= 0 || sig == SIGABRT) {
        syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), sig);
    }
}

JNIEXPORT jboolean JNICALL Java_io_blipit_android_NativeCrash_installHandler(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    if (installed) return JNI_TRUE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return JNI_FALSE;
    size_t n = strlen(p);
    if (n >= sizeof report_path) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return JNI_FALSE;
    }
    memcpy(report_path, p, n + 1);
    (*env)->ReleaseStringUTFChars(env, path, p);
    stack_t alt;
    memset(&alt, 0, sizeof alt);
    alt.ss_size = 64 * 1024;
    alt.ss_sp = malloc(alt.ss_size);
    if (alt.ss_sp && sigaltstack(&alt, NULL) != 0) free(alt.ss_sp);
    struct sigaction action;
    memset(&action, 0, sizeof action);
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (size_t i = 0; i < sizeof watched / sizeof watched[0]; i++) {
        sigaction(watched[i], &action, &previous[watched[i]]);
    }
    installed = 1;
    return JNI_TRUE;
}
