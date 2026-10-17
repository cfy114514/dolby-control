// Separate Android fixture: writes its OWN read-only MAP_PRIVATE data mapping.
// Never opens another process, a live module, an audio service, or a library.
#include "table_transaction.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
struct Context { int memory; int pid; };
int identity(void* opaque) { return static_cast<Context*>(opaque)->pid == getpid(); }
int read_word(void* opaque, dcl_txn::U64 address, dcl_txn::U32* result) {
  return pread(static_cast<Context*>(opaque)->memory, result, 4, static_cast<off_t>(address)) == 4;
}
int write_word(void* opaque, dcl_txn::U64 address, dcl_txn::U32 value) {
  return pwrite(static_cast<Context*>(opaque)->memory, &value, 4, static_cast<off_t>(address)) == 4;
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  const std::string root = argv[1];
  constexpr const char* prefix = "/data/local/tmp/dcl-test.";
  if (root.rfind(prefix, 0) != 0 || root.size() != strlen(prefix) + 32 ||
      root.find_first_not_of("0123456789abcdef", strlen(prefix)) != std::string::npos) return 2;
  struct stat directory{};
  if (lstat(root.c_str(), &directory) || !S_ISDIR(directory.st_mode) || (directory.st_mode & 0077)) return 2;
  std::string path = root + "/cow-data.XXXXXX";
  int file = mkstemp(path.data());
  if (file < 0) return 3;
  const long size = sysconf(_SC_PAGESIZE);
  const unsigned original[3] = {1, 0, 1};
  bool okay = size > 0 && ftruncate(file, size) == 0 && pwrite(file, original, sizeof original, 0) == sizeof original;
  void* mapping = okay ? mmap(nullptr, static_cast<size_t>(size), PROT_READ, MAP_PRIVATE, file, 0) : MAP_FAILED;
  int memory = mapping != MAP_FAILED ? open("/proc/self/mem", O_RDWR | O_CLOEXEC) : -1;
  unsigned on_count = 0, off_count = 0;
  if (memory >= 0) {
    Context context{memory, getpid()};
    dcl_txn::Callbacks callbacks{&context, identity, read_word, write_word};
    dcl_txn::Word words[3];
    for (unsigned i = 0; i < 3; ++i) words[i] = {reinterpret_cast<dcl_txn::U64>(mapping) + i * 4, original[i], 0};
    const auto on = dcl_txn::run(words, 3, 1, callbacks);
    on_count = on.changed_words;
    okay &= on.code == 0 && on.state_after == dcl_txn::Target;
    const auto off = dcl_txn::run(words, 3, 2, callbacks);
    off_count = off.changed_words;
    okay &= off.code == 0 && off.state_after == dcl_txn::Original;
  } else okay = false;
  unsigned disk[3]{};
  bool unchanged = pread(file, disk, sizeof disk, 0) == sizeof disk && memcmp(disk, original, sizeof disk) == 0;
  okay &= unchanged;
  if (memory >= 0) close(memory);
  if (mapping != MAP_FAILED) munmap(mapping, static_cast<size_t>(size));
  close(file);
  okay &= unlink(path.c_str()) == 0;
  printf("{\"ok\":%s,\"fixture\":\"self_readonly_private_mapping\",\"other_process_opened\":false,\"on_changed_words\":%u,\"off_changed_words\":%u,\"disk_unchanged\":%s}\n",
         okay ? "true" : "false", on_count, off_count, unchanged ? "true" : "false");
  return okay ? 0 : 4;
}
