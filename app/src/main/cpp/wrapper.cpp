// Pikafish JNI wrapper
// 把皮卡鱼 UCI 引擎循环接入 Java（System.loadLibrary 加载 .so，绕开 Android 对 exec ELF 的限制）。
//
// 原理：
//   - UCIEngine::loop() 用 std::cin / std::cout 收发命令，这里通过替换 rdbuf 重定向：
//       · std::cin   -> InBuf：从命令队列取行（Java 端 nativeSendCommand 入队）
//       · std::cout  -> OutBuf：按行回调 Java（onNativeOutput）
//   - 引擎在独立线程运行（engineMain），quit 后线程退出并回调 onNativeExit。

#include <jni.h>

#include <condition_variable>
#include <iostream>
#include <memory>
#include <mutex>
#include <queue>
#include <streambuf>
#include <string>
#include <thread>

#include "misc.h"
#include "uci.h"

using namespace Stockfish;

// ---------------- Java 回调句柄（engineMain 线程 / pushLine 使用） ----------------
static JavaVM*   g_vm    = nullptr;
static jobject   g_obj   = nullptr;  // MainActivity 全局引用
static jmethodID g_onOut = nullptr;  // (String)V
static jmethodID g_onEnd = nullptr;  // (I)V

static void pushLine(const std::string& line);

// ---------------- 输出缓冲（std::cout 重定向） ----------------
class OutBuf : public std::streambuf {
   public:
    int_type overflow(int_type c) override {
        if (c != traits_type::eof()) {
            if (c == '\n') {
                pushLine(buf_);
                buf_.clear();
            } else {
                buf_ += static_cast<char>(c);
            }
        }
        return c;
    }

   private:
    std::string buf_;
};

// ---------------- 输入队列 ----------------
static std::queue<std::string> g_cmds;
static std::mutex              g_mtx;
static std::condition_variable g_cv;
static bool                    g_stop = false;

// ---------------- 输入缓冲（std::cin 重定向） ----------------
class InBuf : public std::streambuf {
   public:
    int_type underflow() override {
        if (gptr() < egptr()) return traits_type::to_int_type(*gptr());
        std::string line;
        {
            std::unique_lock<std::mutex> lk(g_mtx);
            g_cv.wait(lk, [] { return !g_cmds.empty() || g_stop; });
            if (g_cmds.empty()) return traits_type::eof();  // stop 且无命令
            line = std::move(g_cmds.front());
            g_cmds.pop();
        }
        cur_ = line + '\n';
        setg(&cur_[0], &cur_[0], &cur_[0] + cur_.size());
        return traits_type::to_int_type(cur_[0]);
    }

   private:
    std::string cur_;
};

// ---------------- 输出一行 -> Java ----------------
static void pushLine(const std::string& line) {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    if (env != nullptr && g_obj != nullptr && g_onOut != nullptr) {
        jstring js = env->NewStringUTF(line.c_str());
        env->CallVoidMethod(g_obj, g_onOut, js);
        env->DeleteLocalRef(js);
    }
}

// ---------------- 引擎主循环线程 ----------------
static void engineMain() {
    {
        CommandLine cli(0, nullptr);
        auto        uci = std::make_unique<UCIEngine>(std::move(cli));
        uci->loop();
    }
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    if (env != nullptr && g_obj != nullptr && g_onEnd != nullptr) {
        env->CallVoidMethod(g_obj, g_onEnd, 0);
    }
    if (env != nullptr) g_vm->DetachCurrentThread();
}

// ---------------- JNI 接口 ----------------
extern "C" {

JNIEXPORT void JNICALL
Java_com_xiangqiai_app_MainActivity_nativeStartEngine(JNIEnv* env, jobject thiz) {
    env->GetJavaVM(&g_vm);
    g_obj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_onOut = env->GetMethodID(cls, "onNativeOutput", "(Ljava/lang/String;)V");
    g_onEnd = env->GetMethodID(cls, "onNativeExit", "(I)V");
    env->DeleteLocalRef(cls);

    g_stop = false;
    std::cin.rdbuf(new InBuf());
    std::cout.rdbuf(new OutBuf());

    std::thread(engineMain).detach();
}

JNIEXPORT void JNICALL
Java_com_xiangqiai_app_MainActivity_nativeSendCommand(JNIEnv* env, jobject, jstring cmd) {
    const char* utf = env->GetStringUTFChars(cmd, nullptr);
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_cmds.push(utf != nullptr ? utf : "");
    }
    env->ReleaseStringUTFChars(cmd, utf);
    g_cv.notify_one();
}

JNIEXPORT void JNICALL
Java_com_xiangqiai_app_MainActivity_nativeStopEngine(JNIEnv* env, jobject) {
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_stop = true;
        g_cmds.push("quit");
    }
    g_cv.notify_all();
    if (g_obj != nullptr) {
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
    }
}

}  // extern "C"
