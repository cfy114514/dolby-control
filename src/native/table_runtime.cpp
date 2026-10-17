#include "table_spec.h"
#include "table_transaction.h"
#include "table_edit_args.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kBase = "/data/adb/dolby_control_app";
constexpr const char* kCmdline = "/system/bin/audioserver";
constexpr unsigned kCount = sizeof(dcl_spec::kRecords) / sizeof(dcl_spec::kRecords[0]);
static_assert(kCount > 0 && kCount <= dcl_txn::kMaximumWords, "Invalid table size");
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "Table words require little endian");
struct Fd { int value = -1; ~Fd() { if (value >= 0) close(value); } };
std::string quote(const std::string& value) {
  std::string result = "\"";
  for (unsigned char c : value) {
    if (c == '\\' || c == '"') { result += '\\'; result += static_cast<char>(c); }
    else if (c < 32) { char escaped[7]; snprintf(escaped, sizeof escaped, "\\u%04x", c); result += escaped; }
    else result += static_cast<char>(c);
  }
  return result + '"';
}
[[noreturn]] void fail(const std::string& message) { throw std::runtime_error(message); }
std::string read_file(const std::string& path, size_t maximum = 1024 * 1024) {
  Fd fd; fd.value = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (fd.value < 0) fail("Cannot read " + path + ": " + strerror(errno));
  std::string data;
  std::array<char, 4096> buffer{};
  for (;;) {
    ssize_t n = read(fd.value, buffer.data(), buffer.size());
    if (n < 0) { if (errno == EINTR) continue; fail("Read failed: " + path); }
    if (!n) return data;
    if (data.size() + static_cast<size_t>(n) > maximum) fail("Oversize input: " + path);
    data.append(buffer.data(), static_cast<size_t>(n));
  }
}
std::string trim(std::string value) {
  while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) value.pop_back();
  return value;
}
std::string fingerprint() {
  char value[PROP_VALUE_MAX]{};
  if (__system_property_get("ro.build.fingerprint", value) <= 0) fail("Missing fingerprint");
  return value;
}

// SHA-256 over the small immutable ELF; no external binary, shell or crypto
// dependency is used from the privileged helper.
uint32_t rotate(uint32_t value, unsigned amount) { return (value >> amount) | (value << (32 - amount)); }
std::string sha256(std::string data) {
  constexpr uint32_t k[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
  uint32_t state[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
  const uint64_t bits = static_cast<uint64_t>(data.size()) * 8;
  data += static_cast<char>(0x80);
  while (data.size() % 64 != 56) data += '\0';
  for (int i = 7; i >= 0; --i) data += static_cast<char>(bits >> (i * 8));
  for (size_t offset = 0; offset < data.size(); offset += 64) {
    uint32_t words[64];
    for (unsigned i = 0; i < 16; ++i) {
      const auto* p = reinterpret_cast<const unsigned char*>(data.data() + offset + i * 4);
      words[i] = uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3];
    }
    for (unsigned i = 16; i < 64; ++i) {
      const uint32_t s0 = rotate(words[i-15],7) ^ rotate(words[i-15],18) ^ (words[i-15] >> 3);
      const uint32_t s1 = rotate(words[i-2],17) ^ rotate(words[i-2],19) ^ (words[i-2] >> 10);
      words[i] = words[i-16] + s0 + words[i-7] + s1;
    }
    uint32_t a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
    for (unsigned i = 0; i < 64; ++i) {
      const uint32_t t1 = h + (rotate(e,6)^rotate(e,11)^rotate(e,25)) + ((e&f)^((~e)&g)) + k[i] + words[i];
      const uint32_t t2 = (rotate(a,2)^rotate(a,13)^rotate(a,22)) + ((a&b)^(a&c)^(b&c));
      h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    state[0]+=a;state[1]+=b;state[2]+=c;state[3]+=d;state[4]+=e;state[5]+=f;state[6]+=g;state[7]+=h;
  }
  std::string result;
  for (uint32_t value : state) { char word[9]; snprintf(word, sizeof word, "%08X", value); result += word; }
  return result;
}
struct Identity { int pid; std::string cmdline; std::string starttime; std::string boot; };
std::string starttime(int pid) {
  std::string stat = read_file("/proc/" + std::to_string(pid) + "/stat", 16384);
  auto close = stat.rfind(')');
  if (close == std::string::npos) fail("Invalid process stat");
  std::istringstream tokens(stat.substr(close + 1));
  std::string token;
  for (unsigned field = 3; field <= 22; ++field) if (!(tokens >> token)) fail("Incomplete process stat");
  if (token.empty() || token.find_first_not_of("0123456789") != std::string::npos) fail("Invalid process starttime");
  return token;
}
bool same_identity(const Identity& identity) {
  try {
    return read_file("/proc/" + std::to_string(identity.pid) + "/cmdline", 4096) == identity.cmdline &&
           starttime(identity.pid) == identity.starttime &&
           trim(read_file("/proc/sys/kernel/random/boot_id", 128)) == identity.boot;
  } catch (...) { return false; }
}
Identity find_process() {
  DIR* directory = opendir("/proc");
  if (!directory) fail("Cannot list proc");
  Identity found{};
  unsigned matches = 0;
  while (dirent* entry = readdir(directory)) {
    const std::string name = entry->d_name;
    if (name.empty() || name.find_first_not_of("0123456789") != std::string::npos) continue;
    try {
      int pid = std::stoi(name);
      const std::string cmdline = read_file("/proc/" + name + "/cmdline", 4096);
      if (cmdline.substr(0, cmdline.find('\0')) != kCmdline) continue;
      ++matches;
      found = {pid, cmdline, starttime(pid), trim(read_file("/proc/sys/kernel/random/boot_id", 128))};
    } catch (...) { continue; }
  }
  closedir(directory);
  if (matches != 1) fail("Expected exactly one audioserver process");
  if (!same_identity(found)) fail("Audioserver identity changed during discovery");
  return found;
}
struct Mapping { uint64_t begin, end, offset, inode; unsigned device_major, device_minor; std::string permissions; };
std::vector<Mapping> mappings(int pid) {
  std::istringstream input(read_file("/proc/" + std::to_string(pid) + "/maps", 4 * 1024 * 1024));
  std::string line;
  std::vector<Mapping> result;
  while (std::getline(input, line)) {
    unsigned long long begin, end, offset, inode;
    unsigned device_major, device_minor;
    char permissions[5]{};
    int consumed = 0;
    if (sscanf(line.c_str(), "%llx-%llx %4s %llx %x:%x %llu %n", &begin, &end,
               permissions, &offset, &device_major, &device_minor, &inode, &consumed) != 7) continue;
    std::string path = line.substr(static_cast<size_t>(consumed));
    if (path != dcl_spec::kLibraryPath) continue;
    result.push_back({begin, end, offset, inode, device_major, device_minor, permissions});
  }
  if (result.empty()) fail("Exact SYSTEM library mapping not found");
  return result;
}
uint64_t address_for(const std::vector<Mapping>& maps, const struct stat& file, uint64_t offset, size_t size) {
  uint64_t address = 0;
  unsigned matches = 0;
  for (const auto& map : maps) {
    if (offset < map.offset || size > map.end - map.begin || offset - map.offset > map.end - map.begin - size) continue;
    if (map.permissions != "r--p") fail("Allowlisted data is not in a private read-only non-executable mapping");
    if (map.inode != static_cast<uint64_t>(file.st_ino) || map.device_major != major(file.st_dev) || map.device_minor != minor(file.st_dev))
      fail("Mapped library inode/device differs from verified disk artifact");
    address = map.begin + offset - map.offset;
    ++matches;
  }
  if (matches != 1) fail("Missing or ambiguous allowlisted data mapping");
  return address;
}
bool read_exact(int fd, uint64_t address, void* output, size_t size) {
  ssize_t result;
  do { result = pread(fd, output, size, static_cast<off_t>(address)); } while (result < 0 && errno == EINTR);
  return result == static_cast<ssize_t>(size);
}
std::string mapping_signature(const std::vector<Mapping>& maps) {
  std::string signature;
  for (const auto& map : maps) {
    signature += std::to_string(map.begin) + ":" + std::to_string(map.end) + ":" +
        std::to_string(map.offset) + ":" + std::to_string(map.inode) + ":" +
        std::to_string(map.device_major) + ":" + std::to_string(map.device_minor) + ":" + map.permissions + "\n";
  }
  return signature;
}
struct Context { Identity identity; int memory; std::string expected_maps; };
int identity_callback(void* opaque) {
  auto& context = *static_cast<Context*>(opaque);
  if (!same_identity(context.identity)) return 0;
  // Do not write through an address after the library is unloaded/remapped.
  try { return mapping_signature(mappings(context.identity.pid)) == context.expected_maps; }
  catch (...) { return 0; }
}
int read_callback(void* opaque, dcl_txn::U64 address, dcl_txn::U32* value) {
  return read_exact(static_cast<Context*>(opaque)->memory, address, value, sizeof *value);
}
int write_callback(void* opaque, dcl_txn::U64 address, dcl_txn::U32 value) {
  const int fd = static_cast<Context*>(opaque)->memory;
  ssize_t result;
  do { result = pwrite(fd, &value, sizeof value, static_cast<off_t>(address)); } while (result < 0 && errno == EINTR);
  return result == sizeof value;
}
void acquire_lock(Fd& lock) {
  struct stat info{};
  if (lstat(kBase, &info) < 0) {
    if (errno != ENOENT || mkdir(kBase, 0700) < 0) fail("Cannot create private module state directory");
    if (lstat(kBase, &info) < 0) fail("Cannot inspect module state directory");
  }
  if (!S_ISDIR(info.st_mode) || info.st_uid != 0 || (info.st_mode & 0022)) fail("Module state directory is not private root-owned storage");
  const std::string path = std::string(kBase) + "/.table-runtime.lock";
  lock.value = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
  if (lock.value < 0 || fstat(lock.value, &info) < 0 || !S_ISREG(info.st_mode) || info.st_uid != 0 || info.st_nlink != 1 || (info.st_mode & 0077))
    fail("Unsafe transaction lock file");
  if (flock(lock.value, LOCK_EX | LOCK_NB) < 0) fail("Another table transaction is active");
}
const char* state_name(int state) {
  return state == dcl_txn::Original ? "original" : state == dcl_txn::Target ? "target" :
      state == dcl_txn::Mixed ? "mixed" : state == dcl_txn::Custom ? "custom" : "unknown";
}
std::string dsp_key_for(const dcl_spec::Record& record) {
  const std::string name = record.name;
  const auto separator = name.find_last_of('_');
  return separator == std::string::npos ? "" : name.substr(separator + 1);
}
void verify_edit_identity(unsigned index, const dcl_spec::Record& record) {
  const std::string key = record.key;
  if (key.empty() || key.size() > 80 || key.find_first_not_of("abcdefghijklmnopqrstuvwxyz-") != std::string::npos ||
      !dcl_edits::profile_allowed(record.profile) ||
      record.minimum > record.maximum || record.original < record.minimum ||
      record.original > record.maximum || record.target < record.minimum || record.target > record.maximum)
    fail("Invalid compiled field metadata");
  uint32_t parameter = 0;
  const std::string name = record.name;
  const std::string dsp_key = dsp_key_for(record);
  if (dsp_key.empty() || dsp_key.size() > 4 || dsp_key.find_first_not_of("abcdefghijklmnopqrstuvwxyz") != std::string::npos)
    fail("Invalid compiled DSP key metadata");
  const std::string prefix = record.profile == -1 ? "Common_" : "profile" + std::to_string(record.profile) + "_";
  if (name.rfind(prefix, 0) != 0) fail("Compiled profile scope metadata mismatch");
  for (unsigned i = 0; i < dsp_key.size(); ++i) parameter |= uint32_t(dsp_key[i]) << (i * 8);
  if (parameter != record.parameter_id || (record.is_flag && (record.minimum != 0 || record.maximum != 1)))
    fail("Compiled edit metadata does not match exact table allowlist");
  for (unsigned i = 0; i < index; ++i) if (dcl_spec::kRecords[i].profile == record.profile && key == dcl_spec::kRecords[i].key)
    fail("Duplicate compiled field metadata");
}
void print_records(const dcl_txn::U32* values) {
  putchar('[');
  for (unsigned i = 0; i < kCount; ++i) {
    const auto& record = dcl_spec::kRecords[i];
    if (i) putchar(',');
    printf("{\"profile\":%d,\"scope\":\"%s\",\"key\":%s,\"dsp_key\":%s,\"original\":%d,\"current\":",
           record.profile, record.profile == -1 ? "common" : "profile", quote(record.key).c_str(),
           quote(dsp_key_for(record)).c_str(), record.original);
    if (values) printf("%d", static_cast<int32_t>(values[i])); else printf("null");
    printf(",\"target\":%d,\"minimum\":%d,\"maximum\":%d,\"is_flag\":%s}",
           record.target, record.minimum, record.maximum, record.is_flag ? "true" : "false");
  }
  putchar(']');
}
const char* code_name(int code) {
  switch (code) {
    case dcl_txn::BadInput: return "invalid_allowlist";
    case dcl_txn::IdentityChanged: return "process_or_mapping_changed";
    case dcl_txn::ReadFailed: return "memory_read_failed";
    case dcl_txn::UnexpectedWord: return "unknown_table_value";
    case dcl_txn::ConcurrentChange: return "concurrent_table_change";
    case dcl_txn::WriteFailed: return "memory_write_failed";
    case dcl_txn::VerifyFailed: return "write_readback_failed";
    default: return "none";
  }
}
} // namespace

int main(int argc, char** argv) {
  std::string command = argc >= 2 ? argv[1] : "";
  Identity identity{};
  dcl_txn::Result result{};
  result.rollback_complete = 1;
  std::string error, hash;
  bool attempted = false;
  dcl_edits::Selection selection{};
  std::vector<dcl_edits::Field> fields;
  dcl_txn::U32 before_values[kCount]{}, after_values[kCount]{};
  try {
    for (unsigned i = 0; i < kCount; ++i) {
      const auto& record = dcl_spec::kRecords[i];
      verify_edit_identity(i, record);
      fields.push_back({record.profile, record.key, record.minimum, record.maximum});
    }
    if (command == "set") {
      const int parsed = dcl_edits::parse(argc, argv, fields.data(), static_cast<unsigned>(fields.size()), selection);
      if (parsed) fail("Invalid fixed field batch: " + std::to_string(parsed));
    } else if ((command != "status" && command != "on" && command != "off") || argc != 2)
      fail("Usage: table-runtime status|on|off OR set PROFILE KEY VALUE [PROFILE KEY VALUE ...]");
    if (!dcl_spec::kReady || getuid() != 0) fail("Root and a completed exact firmware allowlist are required");
    if (fingerprint() != dcl_spec::kFingerprint) fail("Firmware fingerprint mismatch");
    Fd lock;
    if (command != "status") acquire_lock(lock);
    const std::string library = read_file(dcl_spec::kLibraryPath, 1024 * 1024);
    if (library.size() != dcl_spec::kLibrarySize) fail("SYSTEM library size mismatch");
    hash = sha256(library);
    if (hash != dcl_spec::kLibrarySha256) fail("SYSTEM library SHA-256 mismatch");
    struct stat library_stat{};
    if (stat(dcl_spec::kLibraryPath, &library_stat) < 0 || !S_ISREG(library_stat.st_mode)) fail("Cannot stat verified SYSTEM library");
    identity = find_process();
    if (const char* expected = getenv("DCL_EXPECT_PROCESS")) {
      const std::string actual = std::to_string(identity.pid) + ":" + identity.starttime + ":" + identity.boot;
      if (!*expected || actual != expected) fail("Expected audioserver identity no longer matches");
    }
    const auto maps = mappings(identity.pid);
    Context context{identity, -1, mapping_signature(maps)};
    Fd memory;
    memory.value = open(("/proc/" + std::to_string(identity.pid) + "/mem").c_str(),
                        (command == "status" ? O_RDONLY : O_RDWR) | O_CLOEXEC | O_NOFOLLOW);
    if (memory.value < 0) fail("Cannot open audioserver memory: " + std::string(strerror(errno)));
    context.memory = memory.value;
    std::vector<dcl_txn::Word> words;
    for (const auto& record : dcl_spec::kRecords) {
      verify_edit_identity(static_cast<unsigned>(words.size()), record);
      if (record.file_offset < 12 || record.file_offset % 4 || record.file_offset + 4 > library.size()) fail("Invalid fixed record offset");
      const uint64_t address = address_for(maps, library_stat, record.file_offset - 12, 16);
      uint32_t expected[4]{0x40000000U, record.parameter_id, 1U, static_cast<uint32_t>(record.original)};
      if (memcmp(library.data() + record.file_offset - 12, expected, 16) != 0) fail("Allowlist does not match immutable ELF record");
      uint32_t observed[4]{};
      if (!identity_callback(&context) || !read_exact(memory.value, address, observed, sizeof observed)) fail("Cannot preflight table record");
      dcl_txn::Word word{address + 12, static_cast<uint32_t>(record.original), static_cast<uint32_t>(record.target),
                         record.minimum, record.maximum, record.is_flag ? 1U : 0U};
      if (memcmp(observed, expected, 12) != 0 || !dcl_txn::allowed(word, observed[3]))
        fail("Unexpected table record metadata or value outside verified bounds");
      words.push_back(word);
    }
    dcl_txn::Callbacks callbacks{&context, identity_callback, read_callback, write_callback};
    attempted = true;
    result = dcl_txn::run(words.data(), static_cast<unsigned>(words.size()),
                         command == "status" ? 0 : command == "on" ? 1 : command == "off" ? 2 : 3,
                         callbacks, selection.values, selection.selected, before_values, after_values);
    if (result.code) error = code_name(result.code);
  } catch (const std::exception& exception) { error = exception.what(); }
  const bool ok = error.empty();
  const char* transaction = !attempted ? "rejected" : command == "status" ? "status" : !ok ?
      (!result.changed_words && !result.rollback_words && result.rollback_complete ? "rejected" :
       result.rollback_complete ? "rolled_back" : "rollback_failed") : result.changed_words ? "applied" : "unchanged";
  printf("{\"schema\":1,\"ok\":%s,\"command\":%s,\"table_state\":\"%s\",\"pid\":%d,\"process_starttime\":%s,\"boot_id\":%s,\"library_sha256\":%s,\"record_count\":%u,\"changed_words\":%u,\"rollback_words\":%u,\"rollback_complete\":%s,\"dsp_applied\":false,\"requires_profile_reload\":%s,\"transaction\":\"%s\"",
         ok ? "true" : "false", quote(command).c_str(), state_name(result.state_after), identity.pid,
         quote(identity.starttime).c_str(), quote(identity.boot).c_str(), quote(hash).c_str(), kCount,
         result.changed_words, result.rollback_words, result.rollback_complete ? "true" : "false",
         ok && command != "status" ? "true" : "false", transaction);
  printf(",\"records\":");
  print_records(result.state_after != dcl_txn::Unknown ? after_values : nullptr);
  printf(",\"previous_records\":");
  if (command != "status" && result.state_before != dcl_txn::Unknown) print_records(before_values);
  else printf("null");
  printf(",\"table_state_scope\":\"enhancement_flags\",\"process_identity\":%s",
         quote(identity.pid ? std::to_string(identity.pid) + ":" + identity.starttime + ":" + identity.boot : "").c_str());
  if (!ok) printf(",\"error\":%s,\"failed_index\":%u", quote(error).c_str(), result.failed_index);
  puts("}");
  return ok ? 0 : 3;
}
