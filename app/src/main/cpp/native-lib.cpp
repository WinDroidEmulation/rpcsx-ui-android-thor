#include <algorithm>
#include <android/dlext.h>
#include <android/log.h>
#include <cerrno>
#include <cctype>
#include <cstdlib>
#include <dirent.h>
#include <dlfcn.h>
#include <cstring>
#include <jni.h>
#include <optional>
#include <sched.h>
#include <string>
#include <string_view>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utility>

#if defined(__aarch64__)
#include <adrenotools/driver.h>
#include <adrenotools/priv.h>
#endif

struct RPCSXApi {
  bool (*overlayPadData)(int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  bool (*overlayPadMotionData)(float accelX, float accelY, float accelZ,
                               float gyroX, float gyroY, float gyroZ);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  bool (*collectGameInfo)(JNIEnv *env, std::string_view rootDir,
                          long progressId);
  void (*shutdown)();
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*resume)();
  void (*openHomeMenu)();
  bool (*saveState)();
  bool (*loadState)();
  bool (*isFastForwardEnabled)();
  bool (*setFastForwardEnabled)(bool enabled);
  bool (*toggleFastForward)();
  std::string (*getTitleId)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  bool (*usbDeviceEvent)(int fd, int vendorId, int productId, int event);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*isInstallableFile)(jint fd);
  jstring (*getDirInstallPath)(JNIEnv *env, jint fd);
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*systemInfo)();
  void (*loginUser)(std::string_view userId);
  std::string (*getUser)();
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  std::string (*getVersion)();
  void *(*setCustomDriver)(void *driverHandle);
  bool (*setPatchEnabled)(std::string_view hash, std::string_view description,
                          bool enabled);
  bool (*preparePpuCache)(JNIEnv *env, std::string_view path,
                          std::string_view titleId, long progressId);
};

struct RPCSXLibrary : RPCSXApi {
  void *handle = nullptr;

  RPCSXLibrary() = default;
  RPCSXLibrary(const RPCSXLibrary &) = delete;
  RPCSXLibrary(RPCSXLibrary &&other) { swap(other); }
  RPCSXLibrary &operator=(RPCSXLibrary &&other) {
    swap(other);
    return *this;
  }
  ~RPCSXLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(RPCSXLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<RPCSXApi &>(*this), static_cast<RPCSXApi &>(other));
  }

  static std::optional<RPCSXLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, "RPCSX-UI",
                          "Failed to open RPCSX library at %s, error %s", path,
                          ::dlerror());
      return {};
    }

    RPCSXLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "_rpcsx_overlayPadData"));
    result.overlayPadMotionData = reinterpret_cast<decltype(overlayPadMotionData)>(dlsym(handle, "_rpcsx_overlayPadMotionData"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "_rpcsx_initialize"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "_rpcsx_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "_rpcsx_startMainThreadProcessor"));
    result.collectGameInfo = reinterpret_cast<decltype(collectGameInfo)>(dlsym(handle, "_rpcsx_collectGameInfo"));
    result.shutdown = reinterpret_cast<decltype(shutdown)>(dlsym(handle, "_rpcsx_shutdown"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "_rpcsx_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "_rpcsx_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "_rpcsx_kill"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "_rpcsx_resume"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "_rpcsx_openHomeMenu"));
    result.saveState = reinterpret_cast<decltype(saveState)>(dlsym(handle, "_rpcsx_saveState"));
    result.loadState = reinterpret_cast<decltype(loadState)>(dlsym(handle, "_rpcsx_loadState"));
    result.isFastForwardEnabled = reinterpret_cast<decltype(isFastForwardEnabled)>(dlsym(handle, "_rpcsx_isFastForwardEnabled"));
    result.setFastForwardEnabled = reinterpret_cast<decltype(setFastForwardEnabled)>(dlsym(handle, "_rpcsx_setFastForwardEnabled"));
    result.toggleFastForward = reinterpret_cast<decltype(toggleFastForward)>(dlsym(handle, "_rpcsx_toggleFastForward"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "_rpcsx_getTitleId"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "_rpcsx_surfaceEvent"));
    result.usbDeviceEvent = reinterpret_cast<decltype(usbDeviceEvent)>(dlsym(handle, "_rpcsx_usbDeviceEvent"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "_rpcsx_installFw"));
    result.isInstallableFile = reinterpret_cast<decltype(isInstallableFile)>(dlsym(handle, "_rpcsx_isInstallableFile"));
    result.getDirInstallPath = reinterpret_cast<decltype(getDirInstallPath)>(dlsym(handle, "_rpcsx_getDirInstallPath"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "_rpcsx_install"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "_rpcsx_installKey"));
    result.systemInfo = reinterpret_cast<decltype(systemInfo)>(dlsym(handle, "_rpcsx_systemInfo"));
    result.loginUser = reinterpret_cast<decltype(loginUser)>(dlsym(handle, "_rpcsx_loginUser"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "_rpcsx_getUser"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "_rpcsx_settingsGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "_rpcsx_settingsSet"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "_rpcsx_getVersion"));
    result.setCustomDriver = reinterpret_cast<decltype(setCustomDriver)>(dlsym(handle, "_rpcsx_setCustomDriver"));
    result.setPatchEnabled = reinterpret_cast<decltype(setPatchEnabled)>(dlsym(handle, "_rpcsx_setPatchEnabled"));
    result.preparePpuCache = reinterpret_cast<decltype(preparePpuCache)>(dlsym(handle, "_rpcsx_preparePpuCache"));
    // clang-format on

    return result;
  }
};

static RPCSXLibrary rpcsxLib;

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

static bool set_thread_affinity(pid_t tid, jint mask) {
  cpu_set_t set;
  CPU_ZERO(&set);

  for (int cpu = 0; cpu < CPU_SETSIZE && cpu < 32; cpu++) {
    if ((mask & (1 << cpu)) != 0) {
      CPU_SET(cpu, &set);
    }
  }

  if (::sched_setaffinity(tid, sizeof(set), &set) != 0) {
    __android_log_print(ANDROID_LOG_WARN, "RPCSX-UI",
                        "Failed to set affinity for tid %d to mask 0x%x: %s",
                        tid, mask, ::strerror(errno));
    return false;
  }

  return true;
}

static bool is_numeric_name(const char *name) {
  if (name == nullptr || *name == '\0') {
    return false;
  }

  for (const char *cursor = name; *cursor != '\0'; cursor++) {
    if (!std::isdigit(static_cast<unsigned char>(*cursor))) {
      return false;
    }
  }

  return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    rpcsxLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getLibraryVersion(JNIEnv *env, jobject, jstring path) {
  if (auto library = RPCSXLibrary::Open(unwrap(env, path).c_str())) {
    if (auto getVersion = library->getVersion) {
      return wrap(env, getVersion());
    }
  }

  return {};
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_overlayPadData(
    JNIEnv *, jobject, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  return rpcsxLib.overlayPadData(digital1, digital2, leftStickX, leftStickY,
                                 rightStickX, rightStickY);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsPadMotionData(JNIEnv *, jobject) {
  return rpcsxLib.overlayPadMotionData != nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_overlayPadMotionData(JNIEnv *, jobject, jfloat accelX,
                                          jfloat accelY, jfloat accelZ,
                                          jfloat gyroX, jfloat gyroY,
                                          jfloat gyroZ) {
  if (rpcsxLib.overlayPadMotionData == nullptr) {
    return false;
  }

  return rpcsxLib.overlayPadMotionData(accelX, accelY, accelZ, gyroX, gyroY,
                                       gyroZ);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user) {
  return rpcsxLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_processCompilationQueue(JNIEnv *env, jobject) {
  return rpcsxLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_startMainThreadProcessor(JNIEnv *env, jobject) {
  return rpcsxLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_collectGameInfo(
    JNIEnv *env, jobject, jstring jrootDir, jlong progressId) {
  return rpcsxLib.collectGameInfo(env, unwrap(env, jrootDir), progressId);
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_shutdown(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_boot(JNIEnv *env,
                                                            jobject,
                                                            jstring jpath) {
  return rpcsxLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_net_rpcsx_RPCSX_getState(JNIEnv *env,
                                                                jobject) {
  return rpcsxLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_kill(JNIEnv *env,
                                                            jobject) {
  return rpcsxLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_resume(JNIEnv *env,
                                                              jobject) {
  return rpcsxLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_net_rpcsx_RPCSX_openHomeMenu(JNIEnv *env,
                                                                    jobject) {
  return rpcsxLib.openHomeMenu();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_saveState(JNIEnv *, jobject) {
  if (rpcsxLib.saveState == nullptr) {
    return false;
  }

  return rpcsxLib.saveState();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_loadState(JNIEnv *, jobject) {
  if (rpcsxLib.loadState == nullptr) {
    return false;
  }

  return rpcsxLib.loadState();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_isFastForwardEnabled(JNIEnv *, jobject) {
  if (rpcsxLib.isFastForwardEnabled == nullptr) {
    return false;
  }

  return rpcsxLib.isFastForwardEnabled();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setFastForwardEnabled(JNIEnv *, jobject,
                                           jboolean enabled) {
  if (rpcsxLib.setFastForwardEnabled == nullptr) {
    return false;
  }

  return rpcsxLib.setFastForwardEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_toggleFastForward(JNIEnv *, jobject) {
  if (rpcsxLib.toggleFastForward == nullptr) {
    return false;
  }

  return rpcsxLib.toggleFastForward();
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getTitleId(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getTitleId());
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  return rpcsxLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_usbDeviceEvent(
    JNIEnv *env, jobject, jint fd, jint vendorId, jint productId, jint event) {
  return rpcsxLib.usbDeviceEvent(fd, vendorId, productId, event);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_isInstallableFile(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.isInstallableFile(fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getDirInstallPath(JNIEnv *env, jobject, jint fd) {
  return rpcsxLib.getDirInstallPath(env, fd);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_install(JNIEnv *env, jobject, jint fd, jlong progressId) {
  return rpcsxLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  return rpcsxLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_systemInfo(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.systemInfo());
}

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_loginUser(JNIEnv *env, jobject, jstring user_id) {
  return rpcsxLib.loginUser(unwrap(env, user_id));
}

extern "C" JNIEXPORT jstring JNICALL Java_net_rpcsx_RPCSX_getUser(JNIEnv *env,
                                                                  jobject) {
  return wrap(env, rpcsxLib.getUser());
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  return wrap(env, rpcsxLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_net_rpcsx_RPCSX_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  return rpcsxLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setProcessAffinityMask(JNIEnv *, jobject, jint mask) {
  if (mask <= 0) {
    return false;
  }

  DIR *task_dir = ::opendir("/proc/self/task");
  if (task_dir == nullptr) {
    return set_thread_affinity(static_cast<pid_t>(::syscall(SYS_gettid)), mask);
  }

  bool any_success = false;
  while (dirent *entry = ::readdir(task_dir)) {
    if (!is_numeric_name(entry->d_name)) {
      continue;
    }

    const pid_t tid = static_cast<pid_t>(std::atoi(entry->d_name));
    any_success = set_thread_affinity(tid, mask) || any_success;
  }

  ::closedir(task_dir);
  return any_success;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsPpuCachePreparation(JNIEnv *, jobject) {
  return rpcsxLib.preparePpuCache != nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_preparePpuCache(JNIEnv *env, jobject, jstring jpath,
                                     jstring jtitleId, jlong progressId) {
  if (rpcsxLib.preparePpuCache == nullptr) {
    return false;
  }

  return rpcsxLib.preparePpuCache(env, unwrap(env, jpath), unwrap(env, jtitleId),
                                  progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_supportsCustomDriverLoading(JNIEnv *env,
                                                 jobject instance) {
  return access("/dev/kgsl-3d0", F_OK) == 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_getVersion(JNIEnv *env, jobject) {
  return wrap(env, rpcsxLib.getVersion());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setPatchEnabled(JNIEnv *env, jobject, jstring jhash,
                                     jstring jdescription, jboolean jenabled) {
  if (rpcsxLib.setPatchEnabled == nullptr) {
    return false;
  }

  return rpcsxLib.setPatchEnabled(unwrap(env, jhash), unwrap(env, jdescription),
                                  jenabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_rpcsx_RPCSX_setCustomDriver(JNIEnv *env, jobject, jstring jpath,
                                     jstring jlibraryName, jstring jhookDir) {
#ifdef __aarch64__
  if (rpcsxLib.setCustomDriver == nullptr) {
    return false;
  }

  auto path = unwrap(env, jpath);
  void *loader = nullptr;

  if (!path.empty()) {
      auto hookDir = unwrap(env, jhookDir);
      auto libraryName = unwrap(env, jlibraryName);
      __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI", "Loading custom driver %s",
                          path.c_str());

      ::dlerror();
      loader = adrenotools_open_libvulkan(
              RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, (hookDir + "/").c_str(),
              (path + "/").c_str(), libraryName.c_str(), nullptr, nullptr);

      if (loader == nullptr) {
          __android_log_print(ANDROID_LOG_INFO, "RPCSX-UI",
                              "Failed to load custom driver at '%s': %s",
                              path.c_str(), ::dlerror());
          return false;
      }
  }

  auto prevLoader = rpcsxLib.setCustomDriver(loader);
  if (prevLoader != nullptr) {
    ::dlclose(prevLoader);
  }

  return true;
#else
  return false;
#endif // __aarch64__
}
