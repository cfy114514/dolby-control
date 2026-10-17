#pragma once
// Portable transaction engine. No process APIs, allocation or platform library.
namespace dcl_txn {
using U32 = unsigned int;
using U64 = unsigned long long;
using I32 = signed int;
constexpr unsigned kMaximumWords = 256;
// An inverted range preserves the legacy original-or-target-only contract.
struct Word { U64 address; U32 original; U32 target; I32 minimum = 1; I32 maximum = 0; unsigned is_flag = 1; };
struct Callbacks {
  void* context;
  int (*identity)(void*);
  int (*read)(void*, U64, U32*);
  int (*write)(void*, U64, U32);
};
enum State { Unknown = 0, Original = 1, Target = 2, Mixed = 3, Custom = 4 };
enum Code { Success = 0, BadInput = 1, IdentityChanged = 2, ReadFailed = 3,
            UnexpectedWord = 4, ConcurrentChange = 5, WriteFailed = 6, VerifyFailed = 7 };
struct Result {
  int code;
  unsigned failed_index;
  unsigned changed_words;
  unsigned rollback_words;
  int rollback_complete;
  int state_before;
  int state_after;
};
inline bool allowed(const Word& word, U32 value) {
  if (word.minimum <= word.maximum) {
    const I32 signed_value = static_cast<I32>(value);
    return signed_value >= word.minimum && signed_value <= word.maximum;
  }
  return value == word.original || value == word.target;
}
inline State classify(const Word* words, const U32* values, unsigned count) {
  bool original = true, target = true, custom = false;
  for (unsigned i = 0; i < count; ++i) {
    if (!allowed(words[i], values[i])) return Unknown;
    if (!words[i].is_flag) continue; // Overall neutral/stock state describes flags.
    custom |= values[i] != words[i].original && values[i] != words[i].target;
    original &= values[i] == words[i].original;
    target &= values[i] == words[i].target;
  }
  return original ? Original : target ? Target : custom ? Custom : Mixed;
}
inline Result run(const Word* words, unsigned count, int mode, const Callbacks& cb,
                  const U32* requested = nullptr, const unsigned* selected = nullptr,
                  U32* before_output = nullptr, U32* after_output = nullptr) {
  Result result{};
  result.rollback_complete = 1;
  if (!words || !count || count > kMaximumWords || mode < 0 || mode > 3 ||
      (mode == 3 && (!requested || !selected)) ||
      !cb.identity || !cb.read || !cb.write) { result.code = BadInput; return result; }
  if (mode == 3) {
    for (unsigned i = 0; i < count; ++i) if (selected[i] && !allowed(words[i], requested[i])) {
      result.code = BadInput; result.failed_index = i; return result;
    }
  }
  U32 before[kMaximumWords];
  for (unsigned i = 0; i < count; ++i) {
    result.failed_index = i;
    if (!cb.identity(cb.context)) { result.code = IdentityChanged; return result; }
    if (!cb.read(cb.context, words[i].address, &before[i])) { result.code = ReadFailed; return result; }
    if (!allowed(words[i], before[i])) {
      result.code = UnexpectedWord; return result;
    }
    for (unsigned j = 0; j < i; ++j) if (words[i].address == words[j].address) {
      result.code = BadInput; return result;
    }
  }
  result.state_before = classify(words, before, count);
  result.state_after = result.state_before;
  for (unsigned i = 0; i < count; ++i) {
    if (before_output) before_output[i] = before[i];
    if (after_output) after_output[i] = before[i];
  }
  if (mode == 0) return result;
  U32 desired_values[kMaximumWords];
  for (unsigned i = 0; i < count; ++i) desired_values[i] = mode == 1 ? (words[i].is_flag ? words[i].target : before[i]) :
      mode == 2 ? words[i].original : selected[i] ? requested[i] : before[i];
  unsigned touched[kMaximumWords], touched_count = 0;
  for (unsigned i = 0; i < count; ++i) {
    const U32 desired = desired_values[i];
    if (before[i] == desired) continue;
    result.failed_index = i;
    if (!cb.identity(cb.context)) { result.code = IdentityChanged; break; }
    U32 observed = 0;
    if (!cb.read(cb.context, words[i].address, &observed)) { result.code = ReadFailed; break; }
    if (observed != before[i]) { result.code = ConcurrentChange; break; }
    // Include a failed write in rollback: a short pwrite can still modify bytes.
    touched[touched_count++] = i;
    if (!cb.write(cb.context, words[i].address, desired)) { result.code = WriteFailed; break; }
    ++result.changed_words;
    if (!cb.identity(cb.context)) { result.code = IdentityChanged; break; }
    if (!cb.read(cb.context, words[i].address, &observed) || observed != desired) {
      result.code = VerifyFailed; break;
    }
  }
  // Verify every record after the final write, including originally unchanged
  // entries. A concurrent change never gets reported as a complete transaction.
  if (result.code == Success) {
    for (unsigned i = 0; i < count; ++i) {
      result.failed_index = i;
      U32 observed = 0;
      if (!cb.identity(cb.context)) { result.code = IdentityChanged; break; }
      if (!cb.read(cb.context, words[i].address, &observed)) { result.code = ReadFailed; break; }
      if (observed != desired_values[i]) {
        result.code = ConcurrentChange; break;
      }
    }
  }
  if (result.code == Success) {
    result.state_after = classify(words, desired_values, count);
    if (after_output) for (unsigned i = 0; i < count; ++i) after_output[i] = desired_values[i];
    return result;
  }
  result.state_after = Unknown;
  for (unsigned position = touched_count; position > 0; --position) {
    const unsigned i = touched[position - 1];
    if (!cb.identity(cb.context)) { result.rollback_complete = 0; continue; }
    if (!cb.write(cb.context, words[i].address, before[i])) { result.rollback_complete = 0; continue; }
    U32 observed = 0;
    if (!cb.identity(cb.context) || !cb.read(cb.context, words[i].address, &observed) || observed != before[i]) {
      result.rollback_complete = 0; continue;
    }
    ++result.rollback_words;
  }
  if (result.rollback_complete) {
    for (unsigned i = 0; i < count; ++i) {
      U32 observed = 0;
      if (!cb.identity(cb.context) || !cb.read(cb.context, words[i].address, &observed) || observed != before[i]) {
        result.rollback_complete = 0; break;
      }
    }
  }
  if (result.rollback_complete) result.state_after = result.state_before;
  return result;
}
}  // namespace dcl_txn
