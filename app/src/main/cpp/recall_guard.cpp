#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

#define RG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RGNative", __VA_ARGS__)
#define RG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RGNative", __VA_ARGS__)

namespace {

struct Region {
    uintptr_t start;
    uintptr_t end;
};

struct KernelImage {
    uintptr_t base = 0;
    std::string path;
};

std::atomic<bool> g_hooked{false};
std::atomic<bool> g_native_callback_ready{false};
std::atomic<bool> g_kernel_attempted{false};
std::atomic<bool> g_full_scan_attempted{false};
std::atomic_flag g_installing = ATOMIC_FLAG_INIT;
JavaVM* g_java_vm = nullptr;
using HookFunType = int (*)(void*, void*, void**);
using UnhookFunType = int (*)(void*);
using NativeOnModuleLoaded = void (*)(const char*, void*);
struct NativeAPIEntries {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
};
HookFunType g_hook_func = nullptr;
UnhookFunType g_unhook_func = nullptr;
void* g_c2c_original = nullptr;
void* g_group_original = nullptr;
jclass g_hook_entry = nullptr;
jmethodID g_on_native_push = nullptr;

using NativeOnMsfPushFn = void (*)(JNIEnv*, jobject, jlong, jstring, jbyteArray, jobject);
NativeOnMsfPushFn g_on_msf_push_original = nullptr;

bool prepareJavaCallback(JNIEnv* env);
bool installHooks();

constexpr const char* kNativeOnMsfPushSymbol =
        "Java_com_tencent_qqnt_kernel_nativeinterface_"
        "IQQNTWrapperSession_00024CppProxy_native_1onMsfPush";

void C2cRecallSink(void*, void*, void*, int) {
    // Deliberately consume only the destructive NT recall callback.
    RG_LOGI("blocked C2C destructive recall callback");
}

void GroupRecallSink(void*, void*, void*, int) {
    // Deliberately consume only the destructive NT recall callback.
    RG_LOGI("blocked group destructive recall callback");
}

void NativeOnMsfPushHook(JNIEnv* env, jobject thiz, jlong nativeRef, jstring command,
        jbyteArray payload, jobject extraInfo) {
    // Preserve QQ latency semantics: finish the original receive/ack path before Java parsing.
    if (g_on_msf_push_original != nullptr) {
        g_on_msf_push_original(env, thiz, nativeRef, command, payload, extraInfo);
    }
    if (env != nullptr && command != nullptr && payload != nullptr
            && prepareJavaCallback(env)) {
        env->CallStaticVoidMethod(g_hook_entry, g_on_native_push, command, payload);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            RG_LOGE("HookEntry.onNativePush raised an exception");
        }
    }
}

bool prepareJavaCallback(JNIEnv* env) {
    if (g_hook_entry != nullptr && g_on_native_push != nullptr) return true;
    jclass local = env->FindClass("dev/recallguard/qq/HookEntry");
    if (local == nullptr) {
        env->ExceptionClear();
        RG_LOGE("FindClass HookEntry failed");
        return false;
    }
    jmethodID callback = env->GetStaticMethodID(local, "onNativePush", "(Ljava/lang/String;[B)V");
    if (callback == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(local);
        RG_LOGE("GetStaticMethodID onNativePush failed");
        return false;
    }
    auto global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (global == nullptr) return false;
    g_hook_entry = global;
    g_on_native_push = callback;
    return true;
}

std::vector<Region> executableKernelRegions() {
    std::vector<Region> out;
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return out;
    char line[1024];
    while (fgets(line, sizeof(line), fp)) {
        unsigned long long begin = 0, end = 0;
        char perms[8] = {};
        if (sscanf(line, "%llx-%llx %7s", &begin, &end, perms) == 3
                && strchr(perms, 'x') != nullptr && strstr(line, "libkernel.so") != nullptr) {
            out.push_back({static_cast<uintptr_t>(begin), static_cast<uintptr_t>(end)});
        }
    }
    fclose(fp);
    return out;
}

KernelImage kernelImage() {
    KernelImage out;
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return out;
    char line[2048];
    while (fgets(line, sizeof(line), fp)) {
        unsigned long long begin = 0, end = 0, offset = 0;
        char perms[8] = {};
        char path[1536] = {};
        if (sscanf(line, "%llx-%llx %7s %llx %*s %*s %1535s",
                &begin, &end, perms, &offset, path) == 5
                && offset == 0 && strstr(path, "libkernel.so") != nullptr) {
            out.base = static_cast<uintptr_t>(begin);
            out.path = path;
            break;
        }
    }
    fclose(fp);
    return out;
}

void* resolveElf64DynamicSymbol(const KernelImage& image, const char* wanted) {
    if (image.base == 0 || image.path.empty() || wanted == nullptr) return nullptr;
    int fd = open(image.path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return nullptr;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) {
        close(fd);
        return nullptr;
    }
    void* mapped = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) return nullptr;

    void* result = nullptr;
    const auto* bytes = static_cast<const uint8_t*>(mapped);
    const auto* ehdr = reinterpret_cast<const Elf64_Ehdr*>(bytes);
    const bool valid = memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0
            && ehdr->e_ident[EI_CLASS] == ELFCLASS64
            && ehdr->e_shentsize == sizeof(Elf64_Shdr)
            && ehdr->e_shoff <= static_cast<uint64_t>(st.st_size)
            && ehdr->e_shnum <= (static_cast<uint64_t>(st.st_size) - ehdr->e_shoff) / sizeof(Elf64_Shdr);
    if (valid) {
        const auto* sections = reinterpret_cast<const Elf64_Shdr*>(bytes + ehdr->e_shoff);
        for (uint16_t i = 0; i < ehdr->e_shnum && result == nullptr; ++i) {
            const Elf64_Shdr& dynsym = sections[i];
            if (dynsym.sh_type != SHT_DYNSYM || dynsym.sh_entsize != sizeof(Elf64_Sym)
                    || dynsym.sh_link >= ehdr->e_shnum
                    || dynsym.sh_offset > static_cast<uint64_t>(st.st_size)
                    || dynsym.sh_size > static_cast<uint64_t>(st.st_size) - dynsym.sh_offset) continue;
            const Elf64_Shdr& dynstr = sections[dynsym.sh_link];
            if (dynstr.sh_offset > static_cast<uint64_t>(st.st_size)
                    || dynstr.sh_size > static_cast<uint64_t>(st.st_size) - dynstr.sh_offset) continue;
            const auto* symbols = reinterpret_cast<const Elf64_Sym*>(bytes + dynsym.sh_offset);
            const char* strings = reinterpret_cast<const char*>(bytes + dynstr.sh_offset);
            const size_t count = dynsym.sh_size / sizeof(Elf64_Sym);
            for (size_t j = 0; j < count; ++j) {
                const Elf64_Sym& symbol = symbols[j];
                if (symbol.st_name >= dynstr.sh_size || symbol.st_value == 0
                        || ELF64_ST_TYPE(symbol.st_info) != STT_FUNC) continue;
                if (strcmp(strings + symbol.st_name, wanted) == 0) {
                    result = reinterpret_cast<void*>(image.base + symbol.st_value);
                    break;
                }
            }
        }
    }
    munmap(mapped, static_cast<size_t>(st.st_size));
    return result;
}

bool matches(const uint8_t* p, const uint8_t* pattern, const uint8_t* mask, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if ((p[i] & mask[i]) != (pattern[i] & mask[i])) return false;
    }
    return true;
}

bool isArm64FramePrologue(uintptr_t address) {
    const uint32_t inst = *reinterpret_cast<const uint32_t*>(address);
    const uint32_t mask = (0xffu << 24u) | (0xc0u << 16u) | (0x7fu << 8u) | 0xffu;
    const uint32_t expected = (0xa9u << 24u) | (0x80u << 16u) | (0x7bu << 8u) | 0xfdu;
    return (inst & mask) == expected;
}

uintptr_t findFunction(const std::vector<Region>& regions,
        const uint8_t* pattern, const uint8_t* mask, size_t patternSize,
        const int* candidateDeltas, size_t deltaCount) {
    std::vector<uintptr_t> matchesFound;
    for (const Region& r : regions) {
        if (r.end <= r.start || r.end - r.start < patternSize) continue;
        const auto* first = reinterpret_cast<const uint8_t*>(r.start);
        const auto* last = reinterpret_cast<const uint8_t*>(r.end - patternSize);
        for (const uint8_t* p = first; p <= last; p += 4) {
            if (!matches(p, pattern, mask, patternSize)) continue;
            for (size_t i = 0; i < deltaCount; ++i) {
                const uintptr_t candidate = reinterpret_cast<uintptr_t>(p) + candidateDeltas[i];
                if (candidate >= r.start && candidate + sizeof(uint32_t) <= r.end
                        && isArm64FramePrologue(candidate)) {
                    matchesFound.push_back(candidate);
                }
            }
        }
    }
    if (matchesFound.size() != 1) {
        RG_LOGE("AOB expected one function, got %zu", matchesFound.size());
        return 0;
    }
    return matchesFound.front();
}

bool installHooksOnce() {
    if (g_hooked.load()) return true;
    if (g_hook_func == nullptr || g_unhook_func == nullptr) {
        RG_LOGE("modern native hook entries unavailable");
        return false;
    }
    const auto regions = executableKernelRegions();
    if (regions.empty()) return false;
    // Multiple bounded Java/linker triggers may observe the library becoming available. Once it
    // is mapped, perform the expensive AOB pass exactly once for this QQ process.
    if (g_full_scan_attempted.exchange(true)) return false;

    // QQ 9.2.60 (13010), arm64. Wildcards are represented by zero mask bytes.
    static const uint8_t c2cPattern[] = {
        0x09,0x8d,0x40,0xf8, 0x00,0x03,0x00,0xaa, 0x21,0x00,0x80,0x52,
        0xf3,0x03,0x02,0xaa, 0x29,0x00,0x40,0xf9
    };
    static const uint8_t c2cMask[] = {
        0xff,0xff,0xff,0xff, 0x00,0xff,0xff,0xff, 0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff, 0xff,0x00,0xff,0xff
    };
    static const int c2cDeltas[] = {-0x20, -0x24, -0x28, -0x3c};

    static const uint8_t groupPattern[] = {
        0x09,0x8d,0x40,0xf8, 0x29,0x95,0x40,0xf9, 0x00,0x00,0x00,0x94,
        0x00,0x04,0x00,0x36, 0x00,0x02,0x40,0xf9, 0x61,0x00,0x80,0x52
    };
    static const uint8_t groupMask[] = {
        0xff,0xff,0xff,0xff, 0xff,0xff,0xff,0xff, 0x00,0x00,0xff,0xff,
        0x00,0xff,0xff,0xff, 0x00,0xff,0xff,0xff, 0xff,0xff,0xff,0xff
    };
    static const int groupDeltas[] = {-0x44};

    const uintptr_t c2c = findFunction(regions, c2cPattern, c2cMask, sizeof(c2cPattern),
            c2cDeltas, sizeof(c2cDeltas) / sizeof(c2cDeltas[0]));
    const uintptr_t group = findFunction(regions, groupPattern, groupMask, sizeof(groupPattern),
            groupDeltas, sizeof(groupDeltas) / sizeof(groupDeltas[0]));
    if (!c2c || !group) return false;

    void* nativeOnMsfPush = dlsym(RTLD_DEFAULT, kNativeOnMsfPushSymbol);
    if (nativeOnMsfPush == nullptr) {
        nativeOnMsfPush = resolveElf64DynamicSymbol(kernelImage(), kNativeOnMsfPushSymbol);
    }
    if (nativeOnMsfPush == nullptr) {
        RG_LOGE("resolve native_onMsfPush failed");
        return false;
    }

    if (g_hook_func(reinterpret_cast<void*>(c2c), reinterpret_cast<void*>(C2cRecallSink),
            &g_c2c_original) != 0) {
        RG_LOGE("framework hook C2C failed");
        return false;
    }
    if (g_hook_func(reinterpret_cast<void*>(group), reinterpret_cast<void*>(GroupRecallSink),
            &g_group_original) != 0) {
        g_unhook_func(reinterpret_cast<void*>(c2c));
        g_c2c_original = nullptr;
        RG_LOGE("framework hook group failed");
        return false;
    }
    if (g_hook_func(nativeOnMsfPush, reinterpret_cast<void*>(NativeOnMsfPushHook),
            reinterpret_cast<void**>(&g_on_msf_push_original)) != 0) {
        g_unhook_func(reinterpret_cast<void*>(group));
        g_unhook_func(reinterpret_cast<void*>(c2c));
        g_group_original = nullptr;
        g_c2c_original = nullptr;
        RG_LOGE("framework hook native_onMsfPush failed");
        return false;
    }
    g_hooked.store(true);
    RG_LOGI("QQ 9.2.60 hooks installed: c2c=%p group=%p push=%p",
            reinterpret_cast<void*>(c2c), reinterpret_cast<void*>(group), nativeOnMsfPush);
    return true;
}

bool installHooks() {
    if (g_hooked.load()) return true;
    if (g_installing.test_and_set(std::memory_order_acquire)) return false;
    const bool installed = installHooksOnce();
    g_installing.clear(std::memory_order_release);
    return installed;
}

void onLibraryLoaded(const char* name, void*) {
    if (name == nullptr || strstr(name, "libkernel.so") == nullptr) return;
    if (g_kernel_attempted.exchange(true)) return;
    const uint64_t started = static_cast<uint64_t>(clock());
    const bool installed = installHooks();
    const uint64_t elapsedTicks = static_cast<uint64_t>(clock()) - started;
    RG_LOGI("modern libkernel callback path=%s installed=%s cpuTicks=%llu",
            name, installed ? "true" : "false",
            static_cast<unsigned long long>(elapsedTicks));
}

} // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (entries == nullptr || entries->hook_func == nullptr || entries->unhook_func == nullptr) {
        RG_LOGE("native_init received invalid API entries");
        return nullptr;
    }
    g_hook_func = entries->hook_func;
    g_unhook_func = entries->unhook_func;
    g_native_callback_ready.store(true);
    RG_LOGI("modern native callback registered api=%u", entries->version);
    return onLibraryLoaded;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;
    JNIEnv* env = nullptr;
    if (vm == nullptr
            || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        RG_LOGE("JNI_OnLoad could not obtain JNIEnv");
        return JNI_ERR;
    }
    // The module Java class loader may not be ready at this exact linker boundary. The callback
    // resolves HookEntry lazily on the first target push instead of failing the library load.
    prepareJavaCallback(env);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_recallguard_qq_NativeBridge_install(JNIEnv* env, jclass) {
    return installHooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_recallguard_qq_NativeBridge_isInstalled(JNIEnv*, jclass) {
    return g_hooked.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_recallguard_qq_NativeBridge_status(JNIEnv*, jclass) {
    int status = 0;
    if (g_c2c_original != nullptr && g_group_original != nullptr) status |= 1;
    if (g_on_msf_push_original != nullptr && g_hook_entry != nullptr && g_on_native_push != nullptr) status |= 2;
    if (g_hooked.load()) status |= 4;
    if (g_native_callback_ready.load()) status |= 8;
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_recallguard_qq_NativeBridge_markEarlyBoundary(JNIEnv*, jclass) {
    // Retained for ABI compatibility with already optimized local builds.
}
