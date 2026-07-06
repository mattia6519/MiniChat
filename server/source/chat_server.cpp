#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <ctime>
#include <vector>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <process.h>
#include <windows.h>
#include <wincrypt.h>
#include <wininet.h>
using socket_t = SOCKET;
static const socket_t invalid_socket_value = INVALID_SOCKET;

typedef LONG NTSTATUS;
typedef PVOID BCRYPT_ALG_HANDLE;
typedef PVOID BCRYPT_KEY_HANDLE;
#ifndef BCRYPT_SUCCESS
#define BCRYPT_SUCCESS(status) (((NTSTATUS)(status)) >= 0)
#endif
#define MINICHAT_BCRYPT_USE_SYSTEM_PREFERRED_RNG 0x00000002
static const wchar_t* MINICHAT_BCRYPT_AES_ALGORITHM = L"AES";
static const wchar_t* MINICHAT_BCRYPT_CHAINING_MODE = L"ChainingMode";
static const wchar_t* MINICHAT_BCRYPT_CHAIN_MODE_GCM = L"ChainingModeGCM";
static const wchar_t* MINICHAT_BCRYPT_OBJECT_LENGTH = L"ObjectLength";

struct BCryptAuthInfo {
    ULONG cbSize;
    ULONG dwInfoVersion;
    PUCHAR pbNonce;
    ULONG cbNonce;
    PUCHAR pbAuthData;
    ULONG cbAuthData;
    PUCHAR pbTag;
    ULONG cbTag;
    PUCHAR pbMacContext;
    ULONG cbMacContext;
    ULONG cbAAD;
    unsigned long long cbData;
    ULONG dwFlags;
};

typedef NTSTATUS (WINAPI *BCryptOpenAlgorithmProviderFn)(BCRYPT_ALG_HANDLE*, LPCWSTR, LPCWSTR, ULONG);
typedef NTSTATUS (WINAPI *BCryptSetPropertyFn)(PVOID, LPCWSTR, PUCHAR, ULONG, ULONG);
typedef NTSTATUS (WINAPI *BCryptGetPropertyFn)(PVOID, LPCWSTR, PUCHAR, ULONG, ULONG*, ULONG);
typedef NTSTATUS (WINAPI *BCryptGenerateSymmetricKeyFn)(BCRYPT_ALG_HANDLE, BCRYPT_KEY_HANDLE*, PUCHAR, ULONG, PUCHAR, ULONG, ULONG);
typedef NTSTATUS (WINAPI *BCryptEncryptFn)(BCRYPT_KEY_HANDLE, PUCHAR, ULONG, void*, PUCHAR, ULONG, PUCHAR, ULONG, ULONG*, ULONG);
typedef NTSTATUS (WINAPI *BCryptDecryptFn)(BCRYPT_KEY_HANDLE, PUCHAR, ULONG, void*, PUCHAR, ULONG, PUCHAR, ULONG, ULONG*, ULONG);
typedef NTSTATUS (WINAPI *BCryptDestroyKeyFn)(BCRYPT_KEY_HANDLE);
typedef NTSTATUS (WINAPI *BCryptCloseAlgorithmProviderFn)(BCRYPT_ALG_HANDLE, ULONG);
typedef NTSTATUS (WINAPI *BCryptGenRandomFn)(BCRYPT_ALG_HANDLE, PUCHAR, ULONG, ULONG);

struct BCryptApi {
    HMODULE module = nullptr;
    BCryptOpenAlgorithmProviderFn open_algorithm_provider = nullptr;
    BCryptSetPropertyFn set_property = nullptr;
    BCryptGetPropertyFn get_property = nullptr;
    BCryptGenerateSymmetricKeyFn generate_symmetric_key = nullptr;
    BCryptEncryptFn encrypt = nullptr;
    BCryptDecryptFn decrypt = nullptr;
    BCryptDestroyKeyFn destroy_key = nullptr;
    BCryptCloseAlgorithmProviderFn close_algorithm_provider = nullptr;
    BCryptGenRandomFn gen_random = nullptr;
};

class Mutex {
public:
    Mutex() {
        InitializeCriticalSection(&section_);
    }

    ~Mutex() {
        DeleteCriticalSection(&section_);
    }

private:
    friend class LockGuard;
    CRITICAL_SECTION section_;
};

class LockGuard {
public:
    explicit LockGuard(Mutex& mutex) : mutex_(mutex) {
        EnterCriticalSection(&mutex_.section_);
    }

    ~LockGuard() {
        LeaveCriticalSection(&mutex_.section_);
    }

private:
    Mutex& mutex_;
};
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <unistd.h>
#include <mutex>
#include <thread>
using socket_t = int;
static const socket_t invalid_socket_value = -1;

class Mutex {
private:
    friend class LockGuard;
    std::mutex mutex_;
};

class LockGuard {
public:
    explicit LockGuard(Mutex& mutex) : mutex_(mutex) {
        mutex_.mutex_.lock();
    }

    ~LockGuard() {
        mutex_.mutex_.unlock();
    }

private:
    Mutex& mutex_;
};
#endif

#ifndef _WIN32
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#endif

struct User {
    int id = 0;
    std::string name;
    std::string phone;
    std::string password;
};

struct Client {
    User user;
    socket_t socket = invalid_socket_value;
    std::set<std::string> phonebook;
};

struct PendingMessage {
    long long id = 0;
    int from_id = 0;
    int to_id = 0;
    std::string payload;
};

struct OtpRecord {
    std::string phone;
    std::string code;
    long long expires_at = 0;
};

static const char* USERS_FILE = "users.tsv";
static const char* PENDING_FILE = "pending_messages.tsv";
static const char* STORAGE_KEY_FILE = "minichat_store_key";

static Mutex g_mutex;
static std::map<int, User> g_users_by_id;
static std::map<std::string, int> g_user_id_by_phone;
static std::map<int, std::shared_ptr<Client>> g_online_clients;
static std::map<std::string, OtpRecord> g_pending_otps;
static std::vector<PendingMessage> g_pending_messages;
static std::atomic<int> g_next_id{1};
static std::atomic<long long> g_next_message_id{1};
static std::string g_storage_dir = ".";
static std::string g_storage_key;
static bool g_storage_key_loaded = false;

static void close_socket(socket_t socket) {
#ifdef _WIN32
    closesocket(socket);
#else
    close(socket);
#endif
}

static std::string trim(const std::string& value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(start, end - start);
}

static std::string normalize_phone(const std::string& value) {
    std::string result;
    for (char ch : value) {
        if (std::isdigit(static_cast<unsigned char>(ch))) {
            result.push_back(ch);
        }
    }
    if (result.rfind("00", 0) == 0 && result.size() > 2) {
        result = result.substr(2);
    }
    return result;
}

static long long now_seconds() {
    return static_cast<long long>(std::time(nullptr));
}

static std::string getenv_string(const char* key, const std::string& fallback = "") {
    const char* value = std::getenv(key);
    if (value == nullptr) {
        return fallback;
    }
    return value;
}

static bool env_enabled(const char* key) {
    std::string value = getenv_string(key);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value == "1" || value == "true" || value == "yes" || value == "on";
}

static std::string sms_phone_from_input(const std::string& raw_phone) {
    std::string cleaned = trim(raw_phone);
    if (!cleaned.empty() && cleaned[0] == '+') {
        std::string result = "+";
        for (char ch : cleaned) {
            if (std::isdigit(static_cast<unsigned char>(ch))) {
                result.push_back(ch);
            }
        }
        return result.size() > 1 ? result : normalize_phone(raw_phone);
    }
    return normalize_phone(raw_phone);
}

static std::string generate_otp_code() {
    if (env_enabled("MINICHAT_OTP_CONSOLE")) {
        return "000000";
    }
    static std::mt19937 rng(static_cast<unsigned int>(std::time(nullptr)));
    std::uniform_int_distribution<int> dist(0, 999999);
    int value = dist(rng);
    char buffer[7];
    std::snprintf(buffer, sizeof(buffer), "%06d", value);
    return buffer;
}

static const char* base64_table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static std::string base64_encode(const std::string& input) {
    std::string output;
    int value = 0;
    int bits = -6;
    for (unsigned char c : input) {
        value = (value << 8) + c;
        bits += 8;
        while (bits >= 0) {
            output.push_back(base64_table[(value >> bits) & 0x3F]);
            bits -= 6;
        }
    }
    if (bits > -6) {
        output.push_back(base64_table[((value << 8) >> (bits + 8)) & 0x3F]);
    }
    while (output.size() % 4) {
        output.push_back('=');
    }
    return output;
}

static std::string base64_decode(const std::string& input) {
    std::vector<int> table(256, -1);
    for (int i = 0; i < 64; ++i) {
        table[static_cast<unsigned char>(base64_table[i])] = i;
    }

    std::string output;
    int value = 0;
    int bits = -8;
    for (unsigned char c : input) {
        if (c == '=') {
            break;
        }
        if (table[c] == -1) {
            continue;
        }
        value = (value << 6) + table[c];
        bits += 6;
        if (bits >= 0) {
            output.push_back(static_cast<char>((value >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return output;
}

static bool send_all(socket_t socket, const std::string& data) {
    const char* buffer = data.c_str();
    int remaining = static_cast<int>(data.size());
    while (remaining > 0) {
        int sent = send(socket, buffer, remaining, 0);
        if (sent <= 0) {
            return false;
        }
        buffer += sent;
        remaining -= sent;
    }
    return true;
}

static bool send_line(socket_t socket, const std::string& line) {
    return send_all(socket, line + "\n");
}

static bool read_line(socket_t socket, std::string& line) {
    line.clear();
    char ch = 0;
    while (true) {
        int received = recv(socket, &ch, 1, 0);
        if (received <= 0) {
            return false;
        }
        if (ch == '\n') {
            return true;
        }
        if (ch != '\r') {
            line.push_back(ch);
        }
        if (line.size() > 65536) {
            return false;
        }
    }
}

static void send_error(socket_t socket, const std::string& message) {
    send_line(socket, "ERR " + base64_encode(message));
}

static std::vector<std::string> split(const std::string& value, char delimiter) {
    std::vector<std::string> parts;
    std::string current;
    std::istringstream stream(value);
    while (std::getline(stream, current, delimiter)) {
        parts.push_back(current);
    }
    return parts;
}

static bool read_plain_file(const std::string& path, std::string& content) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        content.clear();
        return false;
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    content = buffer.str();
    return true;
}

static std::string directory_name(const std::string& path) {
    size_t pos = path.find_last_of("/\\");
    if (pos == std::string::npos) {
        return ".";
    }
    if (pos == 0) {
        return path.substr(0, 1);
    }
    return path.substr(0, pos);
}

static std::string executable_directory(const char* argv0) {
#ifdef _WIN32
    char buffer[MAX_PATH];
    DWORD length = GetModuleFileNameA(nullptr, buffer, MAX_PATH);
    if (length > 0 && length < MAX_PATH) {
        return directory_name(std::string(buffer, length));
    }
#else
    char buffer[4096];
    ssize_t length = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    if (length > 0) {
        buffer[length] = '\0';
        return directory_name(buffer);
    }
#endif
    if (argv0 != nullptr && argv0[0] != '\0') {
        return directory_name(argv0);
    }
    return ".";
}

static std::string storage_path(const char* filename) {
    if (g_storage_dir.empty() || g_storage_dir == ".") {
        return filename;
    }
#ifdef _WIN32
    const char separator = '\\';
#else
    const char separator = '/';
#endif
    char last = g_storage_dir[g_storage_dir.size() - 1];
    if (last == '/' || last == '\\') {
        return g_storage_dir + filename;
    }
    return g_storage_dir + separator + filename;
}

#ifdef _WIN32
static std::string windows_error(const std::string& fallback) {
    DWORD code = GetLastError();
    if (code == 0) {
        return fallback;
    }
    return fallback + " (" + std::to_string(static_cast<unsigned long long>(code)) + ")";
}

static bool load_bcrypt_api(BCryptApi& api, std::string& error) {
    static BCryptApi cached;
    static bool initialized = false;
    if (!initialized) {
        cached.module = LoadLibraryA("bcrypt.dll");
        if (cached.module != nullptr) {
            cached.open_algorithm_provider = reinterpret_cast<BCryptOpenAlgorithmProviderFn>(GetProcAddress(cached.module, "BCryptOpenAlgorithmProvider"));
            cached.set_property = reinterpret_cast<BCryptSetPropertyFn>(GetProcAddress(cached.module, "BCryptSetProperty"));
            cached.get_property = reinterpret_cast<BCryptGetPropertyFn>(GetProcAddress(cached.module, "BCryptGetProperty"));
            cached.generate_symmetric_key = reinterpret_cast<BCryptGenerateSymmetricKeyFn>(GetProcAddress(cached.module, "BCryptGenerateSymmetricKey"));
            cached.encrypt = reinterpret_cast<BCryptEncryptFn>(GetProcAddress(cached.module, "BCryptEncrypt"));
            cached.decrypt = reinterpret_cast<BCryptDecryptFn>(GetProcAddress(cached.module, "BCryptDecrypt"));
            cached.destroy_key = reinterpret_cast<BCryptDestroyKeyFn>(GetProcAddress(cached.module, "BCryptDestroyKey"));
            cached.close_algorithm_provider = reinterpret_cast<BCryptCloseAlgorithmProviderFn>(GetProcAddress(cached.module, "BCryptCloseAlgorithmProvider"));
            cached.gen_random = reinterpret_cast<BCryptGenRandomFn>(GetProcAddress(cached.module, "BCryptGenRandom"));
        }
        initialized = true;
    }

    api = cached;
    if (api.module == nullptr
            || api.open_algorithm_provider == nullptr
            || api.set_property == nullptr
            || api.get_property == nullptr
            || api.generate_symmetric_key == nullptr
            || api.encrypt == nullptr
            || api.decrypt == nullptr
            || api.destroy_key == nullptr
            || api.close_algorithm_provider == nullptr
            || api.gen_random == nullptr) {
        error = windows_error("bcrypt.dll non disponibile o incompleta");
        return false;
    }
    return true;
}

static bool random_bytes(std::string& target, std::string& error) {
    if (target.empty()) {
        return true;
    }
    BCryptApi api;
    if (!load_bcrypt_api(api, error)) {
        return false;
    }
    NTSTATUS status = api.gen_random(
            nullptr,
            reinterpret_cast<PUCHAR>(&target[0]),
            static_cast<ULONG>(target.size()),
            MINICHAT_BCRYPT_USE_SYSTEM_PREFERRED_RNG
    );
    if (!BCRYPT_SUCCESS(status)) {
        error = "BCryptGenRandom fallita: " + std::to_string(static_cast<long>(status));
        return false;
    }
    return true;
}
#else
static std::string openssl_error() {
    unsigned long code = ERR_get_error();
    if (code == 0) {
        return "errore OpenSSL";
    }
    char buffer[256];
    ERR_error_string_n(code, buffer, sizeof(buffer));
    return buffer;
}

static bool random_bytes(std::string& target, std::string& error) {
    if (target.empty()) {
        return true;
    }
    if (RAND_bytes(reinterpret_cast<unsigned char*>(&target[0]), static_cast<int>(target.size())) != 1) {
        error = "Generazione random fallita: " + openssl_error();
        return false;
    }
    return true;
}
#endif

static bool load_storage_key(std::string& key, std::string& error) {
    if (g_storage_key_loaded) {
        key = g_storage_key;
        return true;
    }

    std::string path = storage_path(STORAGE_KEY_FILE);
    std::string raw;
    if (read_plain_file(path, raw)) {
        std::string decoded = base64_decode(trim(raw));
        if (decoded.size() != 32) {
            error = "Chiave storage non valida in " + path;
            return false;
        }
        g_storage_key = decoded;
        g_storage_key_loaded = true;
        key = g_storage_key;
        return true;
    }

    std::string generated(32, '\0');
    if (!random_bytes(generated, error)) {
        error = "Generazione chiave storage fallita: " + error;
        return false;
    }

    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        error = "Impossibile creare " + path;
        return false;
    }
    output << base64_encode(generated) << "\n";
    output.close();
#ifndef _WIN32
    chmod(path.c_str(), S_IRUSR | S_IWUSR);
#endif

    std::cout << "[storage] nuova chiave locale in " << path << std::endl;
    g_storage_key = generated;
    g_storage_key_loaded = true;
    key = g_storage_key;
    return true;
}

#ifdef _WIN32
static bool open_aes_gcm_key(const std::string& key, BCRYPT_ALG_HANDLE& algorithm, BCRYPT_KEY_HANDLE& key_handle, std::vector<unsigned char>& key_object, std::string& error) {
    algorithm = nullptr;
    key_handle = nullptr;

    BCryptApi api;
    if (!load_bcrypt_api(api, error)) {
        return false;
    }

    NTSTATUS status = api.open_algorithm_provider(&algorithm, MINICHAT_BCRYPT_AES_ALGORITHM, nullptr, 0);
    if (!BCRYPT_SUCCESS(status)) {
        error = "BCryptOpenAlgorithmProvider AES fallita: " + std::to_string(static_cast<long>(status));
        return false;
    }

    status = api.set_property(
            algorithm,
            MINICHAT_BCRYPT_CHAINING_MODE,
            reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(MINICHAT_BCRYPT_CHAIN_MODE_GCM)),
            static_cast<ULONG>((std::wcslen(MINICHAT_BCRYPT_CHAIN_MODE_GCM) + 1) * sizeof(wchar_t)),
            0
    );
    if (!BCRYPT_SUCCESS(status)) {
        error = "BCryptSetProperty GCM fallita: " + std::to_string(static_cast<long>(status));
        api.close_algorithm_provider(algorithm, 0);
        algorithm = nullptr;
        return false;
    }

    DWORD object_length = 0;
    DWORD read = 0;
    status = api.get_property(
            algorithm,
            MINICHAT_BCRYPT_OBJECT_LENGTH,
            reinterpret_cast<PUCHAR>(&object_length),
            sizeof(object_length),
            &read,
            0
    );
    if (!BCRYPT_SUCCESS(status) || object_length == 0) {
        error = "BCryptGetProperty object length fallita: " + std::to_string(static_cast<long>(status));
        api.close_algorithm_provider(algorithm, 0);
        algorithm = nullptr;
        return false;
    }

    key_object.assign(object_length, 0);
    status = api.generate_symmetric_key(
            algorithm,
            &key_handle,
            key_object.data(),
            object_length,
            reinterpret_cast<PUCHAR>(const_cast<char*>(key.data())),
            static_cast<ULONG>(key.size()),
            0
    );
    if (!BCRYPT_SUCCESS(status)) {
        error = "BCryptGenerateSymmetricKey fallita: " + std::to_string(static_cast<long>(status));
        api.close_algorithm_provider(algorithm, 0);
        algorithm = nullptr;
        return false;
    }

    return true;
}

static void close_aes_gcm_key(BCRYPT_ALG_HANDLE algorithm, BCRYPT_KEY_HANDLE key_handle) {
    std::string ignored;
    BCryptApi api;
    if (!load_bcrypt_api(api, ignored)) {
        return;
    }
    if (key_handle != nullptr) {
        api.destroy_key(key_handle);
    }
    if (algorithm != nullptr) {
        api.close_algorithm_provider(algorithm, 0);
    }
}

static bool aes_gcm_encrypt_portable(const std::string& key, const std::string& iv, const std::string& plain, std::string& cipher_text, std::string& tag, std::string& error) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_KEY_HANDLE key_handle = nullptr;
    std::vector<unsigned char> key_object;
    if (!open_aes_gcm_key(key, algorithm, key_handle, key_object, error)) {
        return false;
    }

    BCryptAuthInfo auth_info{};
    auth_info.cbSize = sizeof(auth_info);
    auth_info.dwInfoVersion = 1;
    auth_info.pbNonce = reinterpret_cast<PUCHAR>(const_cast<char*>(iv.data()));
    auth_info.cbNonce = static_cast<ULONG>(iv.size());
    auth_info.pbTag = reinterpret_cast<PUCHAR>(&tag[0]);
    auth_info.cbTag = static_cast<ULONG>(tag.size());

    BCryptApi api;
    if (!load_bcrypt_api(api, error)) {
        close_aes_gcm_key(algorithm, key_handle);
        return false;
    }
    cipher_text.assign(plain.size(), '\0');
    ULONG written = 0;
    NTSTATUS status = api.encrypt(
            key_handle,
            plain.empty() ? nullptr : reinterpret_cast<PUCHAR>(const_cast<char*>(plain.data())),
            static_cast<ULONG>(plain.size()),
            &auth_info,
            nullptr,
            0,
            cipher_text.empty() ? nullptr : reinterpret_cast<PUCHAR>(&cipher_text[0]),
            static_cast<ULONG>(cipher_text.size()),
            &written,
            0
    );

    close_aes_gcm_key(algorithm, key_handle);
    if (!BCRYPT_SUCCESS(status)) {
        error = "BCryptEncrypt AES-GCM fallita: " + std::to_string(static_cast<long>(status));
        return false;
    }
    cipher_text.resize(written);
    return true;
}

static bool aes_gcm_decrypt_portable(const std::string& key, const std::string& iv, const std::string& tag, const std::string& cipher_text, std::string& plain, std::string& error) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_KEY_HANDLE key_handle = nullptr;
    std::vector<unsigned char> key_object;
    if (!open_aes_gcm_key(key, algorithm, key_handle, key_object, error)) {
        return false;
    }

    BCryptAuthInfo auth_info{};
    auth_info.cbSize = sizeof(auth_info);
    auth_info.dwInfoVersion = 1;
    auth_info.pbNonce = reinterpret_cast<PUCHAR>(const_cast<char*>(iv.data()));
    auth_info.cbNonce = static_cast<ULONG>(iv.size());
    auth_info.pbTag = reinterpret_cast<PUCHAR>(const_cast<char*>(tag.data()));
    auth_info.cbTag = static_cast<ULONG>(tag.size());

    BCryptApi api;
    if (!load_bcrypt_api(api, error)) {
        close_aes_gcm_key(algorithm, key_handle);
        return false;
    }
    plain.assign(cipher_text.size(), '\0');
    ULONG written = 0;
    NTSTATUS status = api.decrypt(
            key_handle,
            cipher_text.empty() ? nullptr : reinterpret_cast<PUCHAR>(const_cast<char*>(cipher_text.data())),
            static_cast<ULONG>(cipher_text.size()),
            &auth_info,
            nullptr,
            0,
            plain.empty() ? nullptr : reinterpret_cast<PUCHAR>(&plain[0]),
            static_cast<ULONG>(plain.size()),
            &written,
            0
    );

    close_aes_gcm_key(algorithm, key_handle);
    if (!BCRYPT_SUCCESS(status)) {
        plain.clear();
        error = "Decifratura storage fallita";
        return false;
    }
    plain.resize(written);
    return true;
}
#else
static bool encrypt_storage_blob(const std::string& plain, std::string& encrypted, std::string& error) {
    std::string key;
    if (!load_storage_key(key, error)) {
        return false;
    }

    std::string iv(12, '\0');
    if (!random_bytes(iv, error)) {
        error = "Generazione IV storage fallita: " + error;
        return false;
    }

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (ctx == nullptr) {
        error = "EVP_CIPHER_CTX_new fallita";
        return false;
    }

    bool ok = true;
    int len = 0;
    int cipher_len = 0;
    std::string cipher_text(plain.size() + 16, '\0');
    std::string tag(16, '\0');

    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1
            || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, static_cast<int>(iv.size()), nullptr) != 1
            || EVP_EncryptInit_ex(
                    ctx,
                    nullptr,
                    nullptr,
                    reinterpret_cast<const unsigned char*>(key.data()),
                    reinterpret_cast<const unsigned char*>(iv.data())
            ) != 1) {
        ok = false;
    }

    if (ok && !plain.empty()) {
        if (EVP_EncryptUpdate(
                ctx,
                reinterpret_cast<unsigned char*>(&cipher_text[0]),
                &len,
                reinterpret_cast<const unsigned char*>(plain.data()),
                static_cast<int>(plain.size())
        ) != 1) {
            ok = false;
        }
        cipher_len = len;
    }

    if (ok && EVP_EncryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&cipher_text[0]) + cipher_len, &len) != 1) {
        ok = false;
    }
    cipher_len += len;
    if (ok) {
        cipher_text.resize(static_cast<size_t>(cipher_len));
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, static_cast<int>(tag.size()), &tag[0]) != 1) {
            ok = false;
        }
    }

    if (!ok) {
        error = "Cifratura storage fallita: " + openssl_error();
        EVP_CIPHER_CTX_free(ctx);
        return false;
    }

    EVP_CIPHER_CTX_free(ctx);
    encrypted = "MCF1:" + base64_encode(iv) + ":" + base64_encode(tag) + ":" + base64_encode(cipher_text);
    return true;
}

static bool decrypt_storage_blob(const std::string& raw, std::string& plain, std::string& error) {
    const std::string prefix = "MCF1:";
    if (raw.rfind(prefix, 0) != 0) {
        error = "Formato storage non valido";
        return false;
    }

    size_t first = raw.find(':', prefix.size());
    size_t second = first == std::string::npos ? std::string::npos : raw.find(':', first + 1);
    if (first == std::string::npos || second == std::string::npos) {
        error = "Formato storage incompleto";
        return false;
    }

    std::string iv = base64_decode(raw.substr(prefix.size(), first - prefix.size()));
    std::string tag = base64_decode(raw.substr(first + 1, second - first - 1));
    std::string cipher_text = base64_decode(raw.substr(second + 1));
    if (iv.size() != 12 || tag.size() != 16) {
        error = "Header storage non valido";
        return false;
    }

    std::string key;
    if (!load_storage_key(key, error)) {
        return false;
    }

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (ctx == nullptr) {
        error = "EVP_CIPHER_CTX_new fallita";
        return false;
    }

    bool ok = true;
    int len = 0;
    int plain_len = 0;
    plain.assign(cipher_text.size() + 16, '\0');

    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1
            || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, static_cast<int>(iv.size()), nullptr) != 1
            || EVP_DecryptInit_ex(
                    ctx,
                    nullptr,
                    nullptr,
                    reinterpret_cast<const unsigned char*>(key.data()),
                    reinterpret_cast<const unsigned char*>(iv.data())
            ) != 1) {
        ok = false;
    }

    if (ok && !cipher_text.empty()) {
        if (EVP_DecryptUpdate(
                ctx,
                reinterpret_cast<unsigned char*>(&plain[0]),
                &len,
                reinterpret_cast<const unsigned char*>(cipher_text.data()),
                static_cast<int>(cipher_text.size())
        ) != 1) {
            ok = false;
        }
        plain_len = len;
    }

    if (ok && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, static_cast<int>(tag.size()), &tag[0]) != 1) {
        ok = false;
    }
    if (ok && EVP_DecryptFinal_ex(ctx, reinterpret_cast<unsigned char*>(&plain[0]) + plain_len, &len) != 1) {
        ok = false;
    }
    plain_len += len;

    EVP_CIPHER_CTX_free(ctx);
    if (!ok) {
        plain.clear();
        error = "Decifratura storage fallita";
        return false;
    }

    plain.resize(static_cast<size_t>(plain_len));
    return true;
}
#endif

#ifdef _WIN32
static bool encrypt_storage_blob(const std::string& plain, std::string& encrypted, std::string& error) {
    std::string key;
    if (!load_storage_key(key, error)) {
        return false;
    }

    std::string iv(12, '\0');
    if (!random_bytes(iv, error)) {
        error = "Generazione IV storage fallita: " + error;
        return false;
    }

    std::string tag(16, '\0');
    std::string cipher_text;
    if (!aes_gcm_encrypt_portable(key, iv, plain, cipher_text, tag, error)) {
        return false;
    }

    encrypted = "MCF1:" + base64_encode(iv) + ":" + base64_encode(tag) + ":" + base64_encode(cipher_text);
    return true;
}

static bool decrypt_storage_blob(const std::string& raw, std::string& plain, std::string& error) {
    const std::string prefix = "MCF1:";
    if (raw.rfind(prefix, 0) != 0) {
        error = "Formato storage non valido";
        return false;
    }

    size_t first = raw.find(':', prefix.size());
    size_t second = first == std::string::npos ? std::string::npos : raw.find(':', first + 1);
    if (first == std::string::npos || second == std::string::npos) {
        error = "Formato storage incompleto";
        return false;
    }

    std::string iv = base64_decode(raw.substr(prefix.size(), first - prefix.size()));
    std::string tag = base64_decode(raw.substr(first + 1, second - first - 1));
    std::string cipher_text = base64_decode(raw.substr(second + 1));
    if (iv.size() != 12 || tag.size() != 16) {
        error = "Header storage non valido";
        return false;
    }

    std::string key;
    if (!load_storage_key(key, error)) {
        return false;
    }
    return aes_gcm_decrypt_portable(key, iv, tag, cipher_text, plain, error);
}
#endif

#ifdef _WIN32
static bool dpapi_protect(const std::string& plain, std::string& encrypted, std::string& error) {
    DATA_BLOB input{};
    input.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(plain.data()));
    input.cbData = static_cast<DWORD>(plain.size());

    DATA_BLOB output{};
    if (!CryptProtectData(&input, L"MiniChat server data", nullptr, nullptr, nullptr, 0, &output)) {
        error = "CryptProtectData fallita";
        return false;
    }

    encrypted.assign(reinterpret_cast<char*>(output.pbData), output.cbData);
    LocalFree(output.pbData);
    return true;
}

static bool dpapi_unprotect(const std::string& encrypted, std::string& plain, std::string& error) {
    DATA_BLOB input{};
    input.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(encrypted.data()));
    input.cbData = static_cast<DWORD>(encrypted.size());

    DATA_BLOB output{};
    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr, 0, &output)) {
        error = "CryptUnprotectData fallita";
        return false;
    }

    plain.assign(reinterpret_cast<char*>(output.pbData), output.cbData);
    LocalFree(output.pbData);
    return true;
}
#endif

static bool read_secure_file(const std::string& path, std::string& plain) {
    std::string raw;
    if (!read_plain_file(path, raw)) {
        plain.clear();
        return false;
    }

    const std::string portable_prefix = "MCF1:";
    if (raw.rfind(portable_prefix, 0) == 0) {
        std::string error;
        if (!decrypt_storage_blob(raw, plain, error)) {
            std::cerr << "[storage] impossibile decifrare " << path << ": " << error << std::endl;
            plain.clear();
            return false;
        }
        return true;
    }

    const std::string prefix = "DPAPI1:";
    if (raw.rfind(prefix, 0) != 0) {
        plain = raw;
        return true;
    }

#ifdef _WIN32
    std::string encrypted = base64_decode(raw.substr(prefix.size()));
    std::string error;
    if (!dpapi_unprotect(encrypted, plain, error)) {
        std::cerr << "[storage] impossibile decifrare " << path << ": " << error << std::endl;
        plain.clear();
        return false;
    }
    return true;
#else
    std::cerr << "[storage] file cifrato DPAPI non supportato su questa piattaforma" << std::endl;
    plain.clear();
    return false;
#endif
}

static bool write_secure_file(const std::string& path, const std::string& plain) {
    std::string encrypted;
    std::string error;
    if (!encrypt_storage_blob(plain, encrypted, error)) {
        std::cerr << "[storage] impossibile cifrare " << path << ": " << error << std::endl;
        return false;
    }

    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        return false;
    }
    output << encrypted;
    return true;
}

static std::string url_encode(const std::string& value) {
    static const char* hex = "0123456789ABCDEF";
    std::string encoded;
    for (unsigned char ch : value) {
        if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            encoded.push_back(static_cast<char>(ch));
        } else {
            encoded.push_back('%');
            encoded.push_back(hex[(ch >> 4) & 0x0F]);
            encoded.push_back(hex[ch & 0x0F]);
        }
    }
    return encoded;
}

#ifdef _WIN32
static bool https_post_textbelt(const std::string& body, std::string& response, std::string& error) {
    HINTERNET internet = InternetOpenA("MiniChat/1.0", INTERNET_OPEN_TYPE_PRECONFIG, nullptr, nullptr, 0);
    if (!internet) {
        error = windows_error("InternetOpen fallita");
        return false;
    }

    HINTERNET connect = InternetConnectA(
            internet,
            "textbelt.com",
            INTERNET_DEFAULT_HTTPS_PORT,
            nullptr,
            nullptr,
            INTERNET_SERVICE_HTTP,
            0,
            0
    );
    if (!connect) {
        error = windows_error("InternetConnect fallita");
        InternetCloseHandle(internet);
        return false;
    }

    HINTERNET request = HttpOpenRequestA(
            connect,
            "POST",
            "/text",
            nullptr,
            nullptr,
            nullptr,
            INTERNET_FLAG_SECURE | INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE,
            0
    );
    if (!request) {
        error = windows_error("HttpOpenRequest fallita");
        InternetCloseHandle(connect);
        InternetCloseHandle(internet);
        return false;
    }

    std::string headers = "Content-Type: application/x-www-form-urlencoded\r\n";
    BOOL sent = HttpSendRequestA(
            request,
            headers.c_str(),
            static_cast<DWORD>(headers.length()),
            const_cast<char*>(body.data()),
            static_cast<DWORD>(body.size())
    );
    if (!sent) {
        error = windows_error("Richiesta HTTPS Textbelt fallita");
        InternetCloseHandle(request);
        InternetCloseHandle(connect);
        InternetCloseHandle(internet);
        return false;
    }

    response.clear();
    char buffer[2048];
    DWORD read = 0;
    while (InternetReadFile(request, buffer, sizeof(buffer), &read) && read > 0) {
        response.append(buffer, read);
        read = 0;
    }

    InternetCloseHandle(request);
    InternetCloseHandle(connect);
    InternetCloseHandle(internet);
    return true;
}
#else
static bool connect_tcp_host(const std::string& host, const std::string& port, socket_t& connected, std::string& error) {
    connected = invalid_socket_value;

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    addrinfo* results = nullptr;
    int rc = getaddrinfo(host.c_str(), port.c_str(), &hints, &results);
    if (rc != 0) {
        error = "DNS fallito per " + host;
        return false;
    }

    for (addrinfo* item = results; item != nullptr; item = item->ai_next) {
        socket_t candidate = socket(item->ai_family, item->ai_socktype, item->ai_protocol);
        if (candidate == invalid_socket_value) {
            continue;
        }
        if (connect(candidate, item->ai_addr, static_cast<int>(item->ai_addrlen)) == 0) {
            connected = candidate;
            freeaddrinfo(results);
            return true;
        }
        close_socket(candidate);
    }

    freeaddrinfo(results);
    error = "Connessione TCP fallita verso " + host + ":" + port;
    return false;
}

static bool ssl_write_all(SSL* ssl, const std::string& data, std::string& error) {
    size_t offset = 0;
    while (offset < data.size()) {
        int written = SSL_write(ssl, data.data() + offset, static_cast<int>(data.size() - offset));
        if (written <= 0) {
            error = "SSL_write fallita";
            return false;
        }
        offset += static_cast<size_t>(written);
    }
    return true;
}

static bool https_post_textbelt(const std::string& body, std::string& response, std::string& error) {
    const std::string host = "textbelt.com";
    socket_t tcp_socket = invalid_socket_value;
    if (!connect_tcp_host(host, "443", tcp_socket, error)) {
        return false;
    }

    SSL_CTX* ctx = SSL_CTX_new(TLS_client_method());
    if (ctx == nullptr) {
        close_socket(tcp_socket);
        error = "SSL_CTX_new fallita";
        return false;
    }
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, nullptr);
    if (SSL_CTX_set_default_verify_paths(ctx) != 1) {
        SSL_CTX_free(ctx);
        close_socket(tcp_socket);
        error = "CA store OpenSSL non disponibile";
        return false;
    }

    SSL* ssl = SSL_new(ctx);
    if (ssl == nullptr) {
        SSL_CTX_free(ctx);
        close_socket(tcp_socket);
        error = "SSL_new fallita";
        return false;
    }
    SSL_set_fd(ssl, static_cast<int>(tcp_socket));
    SSL_set_tlsext_host_name(ssl, host.c_str());
#if OPENSSL_VERSION_NUMBER >= 0x10100000L
    SSL_set_hostflags(ssl, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
    SSL_set1_host(ssl, host.c_str());
#endif

    bool ok = true;
    if (SSL_connect(ssl) != 1) {
        error = "Handshake HTTPS Textbelt fallito: " + openssl_error();
        ok = false;
    }
    if (ok && SSL_get_verify_result(ssl) != X509_V_OK) {
        error = "Certificato Textbelt non valido";
        ok = false;
    }

    if (ok) {
        std::ostringstream request;
        request << "POST /text HTTP/1.1\r\n"
                << "Host: " << host << "\r\n"
                << "User-Agent: MiniChat/1.0\r\n"
                << "Content-Type: application/x-www-form-urlencoded\r\n"
                << "Content-Length: " << body.size() << "\r\n"
                << "Connection: close\r\n\r\n"
                << body;
        ok = ssl_write_all(ssl, request.str(), error);
    }

    if (ok) {
        response.clear();
        char buffer[2048];
        int read = 0;
        while ((read = SSL_read(ssl, buffer, sizeof(buffer))) > 0) {
            response.append(buffer, read);
        }
        if (response.empty()) {
            error = "Risposta HTTPS Textbelt vuota";
            ok = false;
        }
    }

    SSL_shutdown(ssl);
    SSL_free(ssl);
    SSL_CTX_free(ctx);
    close_socket(tcp_socket);
    return ok;
}
#endif

static bool send_otp_sms(const std::string& raw_phone, const std::string& otp, std::string& error) {
    if (env_enabled("MINICHAT_OTP_CONSOLE")) {
        std::cout << "[otp] console mode phone=" << raw_phone << " code=" << otp << std::endl;
        return true;
    }

    std::string api_key = getenv_string("MINICHAT_TEXTBELT_KEY", "textbelt");
    std::string phone = sms_phone_from_input(raw_phone);
    std::string message = "MiniChat verification code: " + otp;
    std::string body = "phone=" + url_encode(phone)
            + "&message=" + url_encode(message)
            + "&key=" + url_encode(api_key);

    std::string response;
    if (!https_post_textbelt(body, response, error)) {
        return false;
    }
    if (response.find("\"success\":true") != std::string::npos
            || response.find("\"success\": true") != std::string::npos) {
        return true;
    }
    error = "Invio OTP fallito: " + response;
    return false;
}

static void load_users() {
    std::string content;
    if (!read_secure_file(storage_path(USERS_FILE), content)) {
        return;
    }

    std::istringstream input(content);
    int max_id = 0;
    std::string line;
    while (std::getline(input, line)) {
        if (trim(line).empty()) {
            continue;
        }

        std::vector<std::string> parts = split(line, '\t');
        if (parts.size() < 4) {
            continue;
        }

        try {
            User user;
            user.id = std::stoi(parts[0]);
            user.name = base64_decode(parts[1]);
            user.phone = normalize_phone(parts[2]);
            user.password = base64_decode(parts[3]);
            if (user.id <= 0 || user.name.empty() || user.phone.empty() || user.password.empty()) {
                continue;
            }
            g_users_by_id[user.id] = user;
            g_user_id_by_phone[user.phone] = user.id;
            max_id = std::max(max_id, user.id);
        } catch (...) {
            continue;
        }
    }
    g_next_id = max_id + 1;
}

static bool save_users_locked() {
    std::ostringstream output;
    for (const auto& pair : g_users_by_id) {
        const User& user = pair.second;
        output << user.id << '\t'
               << base64_encode(user.name) << '\t'
               << user.phone << '\t'
               << base64_encode(user.password) << '\n';
    }
    return write_secure_file(storage_path(USERS_FILE), output.str());
}

static void load_pending_messages() {
    std::string content;
    if (!read_secure_file(storage_path(PENDING_FILE), content)) {
        return;
    }

    std::istringstream input(content);
    long long max_id = 0;
    std::string line;
    while (std::getline(input, line)) {
        if (trim(line).empty()) {
            continue;
        }

        std::vector<std::string> parts = split(line, '\t');
        if (parts.size() < 4) {
            continue;
        }

        try {
            PendingMessage message;
            message.id = std::stoll(parts[0]);
            message.from_id = std::stoi(parts[1]);
            message.to_id = std::stoi(parts[2]);
            message.payload = parts[3];
            if (message.id <= 0 || message.from_id <= 0 || message.to_id <= 0 || message.payload.empty()) {
                continue;
            }
            if (g_users_by_id.find(message.from_id) == g_users_by_id.end()
                    || g_users_by_id.find(message.to_id) == g_users_by_id.end()) {
                continue;
            }
            g_pending_messages.push_back(message);
            max_id = std::max(max_id, message.id);
        } catch (...) {
            continue;
        }
    }
    g_next_message_id = max_id + 1;
}

static bool save_pending_messages_locked() {
    std::ostringstream output;
    for (const PendingMessage& message : g_pending_messages) {
        output << message.id << '\t'
               << message.from_id << '\t'
               << message.to_id << '\t'
               << message.payload << '\n';
    }
    return write_secure_file(storage_path(PENDING_FILE), output.str());
}

static bool queue_pending_message_locked(int from_id, int to_id, const std::string& payload, std::string& error) {
    PendingMessage message;
    message.id = g_next_message_id.fetch_add(1);
    message.from_id = from_id;
    message.to_id = to_id;
    message.payload = payload;
    g_pending_messages.push_back(message);

    if (!save_pending_messages_locked()) {
        g_pending_messages.pop_back();
        error = "Impossibile salvare il messaggio";
        return false;
    }
    return true;
}

static std::vector<PendingMessage> pending_messages_for_user(int user_id) {
    LockGuard lock(g_mutex);
    std::vector<PendingMessage> messages;
    for (const PendingMessage& message : g_pending_messages) {
        if (message.to_id == user_id) {
            messages.push_back(message);
        }
    }
    return messages;
}

static void remove_pending_messages(const std::vector<long long>& delivered_ids) {
    if (delivered_ids.empty()) {
        return;
    }

    LockGuard lock(g_mutex);
    g_pending_messages.erase(
            std::remove_if(
                    g_pending_messages.begin(),
                    g_pending_messages.end(),
                    [&delivered_ids](const PendingMessage& message) {
                        return std::find(delivered_ids.begin(), delivered_ids.end(), message.id) != delivered_ids.end();
                    }
            ),
            g_pending_messages.end()
    );
    save_pending_messages_locked();
}

static void deliver_pending_messages_to(const std::shared_ptr<Client>& client) {
    std::vector<PendingMessage> messages = pending_messages_for_user(client->user.id);
    std::vector<long long> delivered_ids;

    for (const PendingMessage& message : messages) {
        std::string forwarded = "MSG " + std::to_string(message.from_id) + " " + message.payload;
        if (!send_line(client->socket, forwarded)) {
            break;
        }
        delivered_ids.push_back(message.id);
        std::cout << "[deliver] pending #" << message.id << " " << message.from_id << " -> " << message.to_id << std::endl;
    }

    remove_pending_messages(delivered_ids);
}

static bool register_user(const std::string& name, const std::string& phone, const std::string& password, User& created, std::string& error) {
    std::string normalized_phone = normalize_phone(phone);
    std::string clean_name = trim(name);
    if (clean_name.empty() || clean_name.size() > 40) {
        error = "Nome non valido";
        return false;
    }
    if (normalized_phone.size() < 5 || normalized_phone.size() > 20) {
        error = "Numero non valido";
        return false;
    }
    if (password.size() < 4 || password.size() > 64) {
        error = "Password non valida";
        return false;
    }

    LockGuard lock(g_mutex);
    if (g_user_id_by_phone.find(normalized_phone) != g_user_id_by_phone.end()) {
        error = "Numero gia registrato";
        return false;
    }

    User user;
    user.id = g_next_id.fetch_add(1);
    user.name = clean_name;
    user.phone = normalized_phone;
    user.password = password;
    g_users_by_id[user.id] = user;
    g_user_id_by_phone[user.phone] = user.id;

    if (!save_users_locked()) {
        g_users_by_id.erase(user.id);
        g_user_id_by_phone.erase(user.phone);
        error = "Impossibile salvare utenti";
        return false;
    }

    created = user;
    return true;
}

static bool begin_registration_otp(const std::string& raw_phone, std::string& error) {
    std::string normalized_phone = normalize_phone(raw_phone);
    if (normalized_phone.size() < 5 || normalized_phone.size() > 20) {
        error = "Numero non valido";
        return false;
    }

    {
        LockGuard lock(g_mutex);
        if (g_user_id_by_phone.find(normalized_phone) != g_user_id_by_phone.end()) {
            error = "Numero gia registrato";
            return false;
        }
    }

    std::string otp = generate_otp_code();
    if (!send_otp_sms(raw_phone, otp, error)) {
        return false;
    }

    OtpRecord record;
    record.phone = normalized_phone;
    record.code = otp;
    record.expires_at = now_seconds() + 300;
    {
        LockGuard lock(g_mutex);
        g_pending_otps[normalized_phone] = record;
    }
    return true;
}

static bool verify_registration_otp(const std::string& raw_phone, const std::string& otp, std::string& error) {
    std::string normalized_phone = normalize_phone(raw_phone);
    LockGuard lock(g_mutex);
    auto it = g_pending_otps.find(normalized_phone);
    if (it == g_pending_otps.end()) {
        error = "OTP non richiesto";
        return false;
    }
    if (it->second.expires_at < now_seconds()) {
        g_pending_otps.erase(it);
        error = "OTP scaduto";
        return false;
    }
    if (it->second.code != trim(otp)) {
        error = "OTP non valido";
        return false;
    }
    g_pending_otps.erase(it);
    return true;
}

static bool login_user(const std::string& phone, const std::string& password, User& user, std::string& error) {
    std::string normalized_phone = normalize_phone(phone);
    LockGuard lock(g_mutex);
    auto phone_it = g_user_id_by_phone.find(normalized_phone);
    if (phone_it == g_user_id_by_phone.end()) {
        error = "Utente non registrato";
        return false;
    }

    auto user_it = g_users_by_id.find(phone_it->second);
    if (user_it == g_users_by_id.end() || user_it->second.password != password) {
        error = "Credenziali non valide";
        return false;
    }

    user = user_it->second;
    return true;
}

static std::shared_ptr<Client> find_online_client(int id) {
    LockGuard lock(g_mutex);
    auto it = g_online_clients.find(id);
    return it == g_online_clients.end() ? nullptr : it->second;
}

static bool user_can_reach_locked(const Client& client, int target_id) {
    auto target_it = g_users_by_id.find(target_id);
    if (target_it == g_users_by_id.end()) {
        return false;
    }
    return client.phonebook.find(target_it->second.phone) != client.phonebook.end();
}

static std::string contacts_line_for_locked(const Client& client) {
    std::ostringstream contacts;
    contacts << "CONTACTS";
    bool first = true;
    for (const std::string& phone : client.phonebook) {
        auto phone_it = g_user_id_by_phone.find(phone);
        if (phone_it == g_user_id_by_phone.end()) {
            continue;
        }

        auto user_it = g_users_by_id.find(phone_it->second);
        if (user_it == g_users_by_id.end() || user_it->second.id == client.user.id) {
            continue;
        }

        bool online = g_online_clients.find(user_it->second.id) != g_online_clients.end();
        contacts << (first ? " " : ";")
                 << user_it->second.id << ":"
                 << base64_encode(user_it->second.name) << ":"
                 << user_it->second.phone << ":"
                 << (online ? "1" : "0");
        first = false;
    }
    return contacts.str();
}

static std::vector<std::pair<socket_t, std::string>> contact_updates_for_all() {
    LockGuard lock(g_mutex);
    std::vector<std::pair<socket_t, std::string>> updates;
    for (const auto& pair : g_online_clients) {
        updates.push_back(std::make_pair(pair.second->socket, contacts_line_for_locked(*pair.second)));
    }
    return updates;
}

static void send_contacts_to_all() {
    std::vector<std::pair<socket_t, std::string>> updates = contact_updates_for_all();
    for (const auto& update : updates) {
        send_line(update.first, update.second);
    }
}

static void sync_contacts(const std::shared_ptr<Client>& client, const std::string& encoded_phone_list) {
    std::set<std::string> phonebook;
    std::vector<std::string> encoded_phones = split(encoded_phone_list, ';');
    for (const std::string& encoded_phone : encoded_phones) {
        std::string phone = normalize_phone(base64_decode(encoded_phone));
        if (!phone.empty() && phone != client->user.phone) {
            phonebook.insert(phone);
        }
        if (phonebook.size() >= 500) {
            break;
        }
    }

    std::string contacts_line;
    {
        LockGuard lock(g_mutex);
        client->phonebook = phonebook;
        contacts_line = contacts_line_for_locked(*client);
    }
    send_line(client->socket, contacts_line);
}

static bool parse_auth_line(const std::string& line, const std::string& command, std::vector<std::string>& decoded) {
    std::string prefix = command + " ";
    if (line.rfind(prefix, 0) != 0) {
        return false;
    }

    std::vector<std::string> parts = split(line.substr(prefix.size()), ' ');
    decoded.clear();
    for (const std::string& part : parts) {
        decoded.push_back(base64_decode(part));
    }
    return true;
}

static int authenticate(socket_t socket, const std::string& line, User& user) {
    std::vector<std::string> fields;
    std::string error;
    if (parse_auth_line(line, "REGISTER_BEGIN", fields)) {
        if (fields.size() < 1) {
            send_error(socket, "Richiesta OTP non valida");
            return -1;
        }
        if (!begin_registration_otp(fields[0], error)) {
            send_error(socket, error);
            return -1;
        }
        send_line(socket, "OTP_SENT");
        return 0;
    } else if (parse_auth_line(line, "REGISTER_VERIFY", fields)) {
        if (fields.size() < 3) {
            send_error(socket, "Registrazione non valida");
            return -1;
        }
        if (!verify_registration_otp(fields[0], fields[2], error)) {
            send_error(socket, error);
            return -1;
        }
        std::string normalized_phone = normalize_phone(fields[0]);
        if (!register_user(normalized_phone, normalized_phone, fields[1], user, error)) {
            send_error(socket, error);
            return -1;
        }
        std::cout << "[register] #" << user.id << " " << user.name << " " << user.phone << std::endl;
    } else if (parse_auth_line(line, "REGISTER", fields)) {
        send_error(socket, "Registrazione richiede OTP");
        return -1;
    } else if (parse_auth_line(line, "LOGIN", fields)) {
        if (fields.size() < 2) {
            send_error(socket, "Login non valido");
            return -1;
        }
        if (!login_user(fields[0], fields[1], user, error)) {
            send_error(socket, error);
            return -1;
        }
    } else {
        send_error(socket, "Comando auth non valido");
        return -1;
    }

    send_line(socket, "OK " + std::to_string(user.id) + " " + base64_encode(user.name) + " " + base64_encode(user.phone));
    return 1;
}

static void remove_client(int id, socket_t socket) {
    LockGuard lock(g_mutex);
    auto it = g_online_clients.find(id);
    if (it != g_online_clients.end() && it->second->socket == socket) {
        g_online_clients.erase(it);
    }
}

static void handle_client(socket_t socket) {
    std::string line;
    if (!read_line(socket, line)) {
        close_socket(socket);
        return;
    }

    User user;
    int auth_result = authenticate(socket, line, user);
    if (auth_result <= 0) {
        close_socket(socket);
        return;
    }

    auto client = std::make_shared<Client>();
    client->user = user;
    client->socket = socket;

    std::shared_ptr<Client> replaced_client;
    {
        LockGuard lock(g_mutex);
        auto existing = g_online_clients.find(user.id);
        if (existing != g_online_clients.end()) {
            replaced_client = existing->second;
        }
        g_online_clients[user.id] = client;
    }
    if (replaced_client && replaced_client->socket != socket) {
        close_socket(replaced_client->socket);
        std::cout << "[takeover] #" << user.id << " nuova connessione sostituisce la precedente" << std::endl;
    }

    std::cout << "[join] #" << user.id << " " << user.name << std::endl;
    send_contacts_to_all();
    deliver_pending_messages_to(client);

    while (read_line(socket, line)) {
        if (line == "PING") {
            send_line(socket, "PONG");
            continue;
        }

        const std::string sync_prefix = "SYNC ";
        if (line.rfind(sync_prefix, 0) == 0) {
            sync_contacts(client, line.substr(sync_prefix.size()));
            continue;
        }

        const std::string msg_prefix = "MSG ";
        if (line.rfind(msg_prefix, 0) != 0) {
            send_error(socket, "Comando non valido");
            continue;
        }

        size_t id_start = msg_prefix.size();
        size_t id_end = line.find(' ', id_start);
        if (id_end == std::string::npos) {
            send_error(socket, "Messaggio non valido");
            continue;
        }

        int to_id = 0;
        try {
            to_id = std::stoi(line.substr(id_start, id_end - id_start));
        } catch (...) {
            send_error(socket, "Destinatario non valido");
            continue;
        }

        std::string payload = line.substr(id_end + 1);
        if (payload.empty()) {
            send_error(socket, "Messaggio vuoto");
            continue;
        }

        std::shared_ptr<Client> recipient;
        std::string queue_error;
        bool queued = false;
        {
            LockGuard lock(g_mutex);
            if (!user_can_reach_locked(*client, to_id)) {
                send_error(socket, "Destinatario non presente nella tua rubrica");
                continue;
            }
            auto recipient_it = g_online_clients.find(to_id);
            if (recipient_it != g_online_clients.end()) {
                recipient = recipient_it->second;
            } else if (queue_pending_message_locked(user.id, to_id, payload, queue_error)) {
                queued = true;
            }
        }

        if (queued) {
            send_line(socket, "QUEUED " + std::to_string(to_id) + " " + payload);
            std::cout << "[queue] #" << user.id << " -> #" << to_id << std::endl;
            continue;
        }

        if (!recipient) {
            send_error(socket, queue_error.empty() ? "Destinatario offline" : queue_error);
            continue;
        }

        std::string forwarded = "MSG " + std::to_string(user.id) + " " + payload;
        if (!send_line(recipient->socket, forwarded)) {
            bool saved_after_failure = false;
            {
                LockGuard lock(g_mutex);
                saved_after_failure = queue_pending_message_locked(user.id, to_id, payload, queue_error);
            }
            if (saved_after_failure) {
                send_line(socket, "QUEUED " + std::to_string(to_id) + " " + payload);
                std::cout << "[queue] #" << user.id << " -> #" << to_id << " after failed direct send" << std::endl;
            } else {
                send_error(socket, queue_error.empty() ? "Invio al destinatario fallito" : queue_error);
            }
        } else {
            send_line(socket, "SENT " + std::to_string(to_id) + " " + payload);
            std::cout << "[msg] #" << user.id << " -> #" << to_id << std::endl;
        }
    }

    std::cout << "[leave] #" << user.id << " " << user.name << std::endl;
    remove_client(user.id, socket);
    close_socket(socket);
    send_contacts_to_all();
}

#ifdef _WIN32
static unsigned __stdcall client_thread(void* arg) {
    socket_t client_socket = static_cast<socket_t>(reinterpret_cast<uintptr_t>(arg));
    handle_client(client_socket);
    return 0;
}

static void start_client_thread(socket_t client_socket) {
    uintptr_t handle = _beginthreadex(
            nullptr,
            0,
            client_thread,
            reinterpret_cast<void*>(static_cast<uintptr_t>(client_socket)),
            0,
            nullptr
    );
    if (handle == 0) {
        close_socket(client_socket);
        return;
    }
    CloseHandle(reinterpret_cast<HANDLE>(handle));
}
#else
static void start_client_thread(socket_t client_socket) {
    std::thread(handle_client, client_socket).detach();
}
#endif

int main(int argc, char** argv) {
    int port = 5555;
    if (argc > 1) {
        port = std::atoi(argv[1]);
    }
    g_storage_dir = executable_directory(argc > 0 ? argv[0] : nullptr);

#ifdef _WIN32
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        std::cerr << "WSAStartup fallita" << std::endl;
        return 1;
    }
#endif

    load_users();
    load_pending_messages();

    socket_t listen_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_socket == invalid_socket_value) {
        std::cerr << "socket() fallita" << std::endl;
        return 1;
    }

    int reuse = 1;
#ifdef _WIN32
    setsockopt(listen_socket, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
#else
    setsockopt(listen_socket, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
#endif

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(static_cast<unsigned short>(port));

    if (bind(listen_socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        std::cerr << "bind() fallita sulla porta " << port << std::endl;
        close_socket(listen_socket);
        return 1;
    }

    if (listen(listen_socket, SOMAXCONN) != 0) {
        std::cerr << "listen() fallita" << std::endl;
        close_socket(listen_socket);
        return 1;
    }

    std::cout << "MiniChat server in ascolto su 0.0.0.0:" << port << std::endl;
    std::cout << "Storage in " << g_storage_dir << std::endl;
    std::cout << "Utenti persistenti in " << storage_path(USERS_FILE) << std::endl;
    std::cout << "Chiave storage in " << storage_path(STORAGE_KEY_FILE) << std::endl;
    std::cout << "OTP via Textbelt. Env: MINICHAT_TEXTBELT_KEY oppure MINICHAT_OTP_CONSOLE=1 per test locale." << std::endl;
    std::cout << "Protocollo: REGISTER_BEGIN telefono | REGISTER_VERIFY telefono password otp | LOGIN telefono password | SYNC rubrica | MSG id testo" << std::endl;

    while (true) {
        sockaddr_in client_address{};
#ifdef _WIN32
        int client_length = sizeof(client_address);
#else
        socklen_t client_length = sizeof(client_address);
#endif
        socket_t client_socket = accept(listen_socket, reinterpret_cast<sockaddr*>(&client_address), &client_length);
        if (client_socket == invalid_socket_value) {
            continue;
        }
        start_client_thread(client_socket);
    }

    close_socket(listen_socket);
#ifdef _WIN32
    WSACleanup();
#endif
    return 0;
}
