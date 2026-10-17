#pragma once
// Fixed profile/key API. No caller-selected memory addresses or parameter IDs.
namespace dcl_edits {
constexpr unsigned kMaximum = 256;
struct Field { int profile; const char* key; int minimum; int maximum; };
struct Selection { unsigned values[kMaximum]; unsigned selected[kMaximum]; unsigned expected[kMaximum]; };
inline bool profile_allowed(int profile) {
  return profile == -1 || (profile >= 0 && profile <= 3) || profile == 8;
}
inline bool equal(const char* a, const char* b) {
  while (*a && *a == *b) { ++a; ++b; }
  return *a == *b;
}
inline bool integer(const char* text, int& result) {
  if (!text) return false;
  bool negative = *text == '-';
  if (negative) ++text;
  if (!*text || (*text == '0' && text[1])) return false;
  unsigned long long value = 0;
  unsigned digits = 0;
  while (*text) {
    if (*text < '0' || *text > '9' || ++digits > 10) return false;
    value = value * 10 + unsigned(*text++ - '0');
    if (value > (negative ? 2147483648ULL : 2147483647ULL)) return false;
  }
  if (negative && !value) return false;
  result = negative ? static_cast<int>(0U - static_cast<unsigned>(value)) : static_cast<int>(value);
  return true;
}
inline int parse(int argc, const char* const* argv, const Field* fields,
                 unsigned count, Selection& output) {
  for (unsigned i = 0; i < kMaximum; ++i) {
    output.values[i] = 0; output.selected[i] = 0; output.expected[i] = 0;
  }
  if (!argv || !fields || !count || count > kMaximum || argc < 2 || !argv[1]) return 1;
  const bool restore = equal(argv[1], "restore");
  if (!restore && !equal(argv[1], "set")) return 1;
  const int width = restore ? 4 : 3;
  if (argc < 2 + width || argc > 2 + int(kMaximum) * width || (argc - 2) % width) return 1;
  for (int i = 1; i < argc; ++i) if (!argv[i]) return 1;
  Selection candidate;
  for (unsigned i = 0; i < count; ++i) {
    candidate.values[i] = 0; candidate.selected[i] = 0; candidate.expected[i] = 0;
  }
  for (int i = 2; i < argc; i += width) {
    int profile = 0, value = 0;
    if (!integer(argv[i], profile) || !profile_allowed(profile)) return 2;
    unsigned field = count;
    for (unsigned j = 0; j < count; ++j) if (profile == fields[j].profile && fields[j].key && equal(argv[i + 1], fields[j].key)) {
      if (field != count) return 6; // Invalid duplicate metadata, never choose one.
      field = j;
    }
    if (field == count) return 3;
    if (restore) {
      int expected = 0;
      if (!integer(argv[i + 2], expected) || expected < fields[field].minimum || expected > fields[field].maximum) return 4;
      candidate.expected[field] = static_cast<unsigned>(expected);
    }
    if (!integer(argv[i + width - 1], value) || value < fields[field].minimum || value > fields[field].maximum) return 4;
    if (candidate.selected[field]) return 5;
    candidate.selected[field] = 1;
    candidate.values[field] = static_cast<unsigned>(value);
  }
  for (unsigned i = 0; i < count; ++i) {
    output.values[i] = candidate.values[i];
    output.selected[i] = candidate.selected[i];
    output.expected[i] = candidate.expected[i];
  }
  return 0;
}
} // namespace dcl_edits
