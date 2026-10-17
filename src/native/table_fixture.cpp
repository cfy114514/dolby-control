#include "table_transaction.h"
#include "table_edit_args.h"
#ifdef _WIN32
#define DCL_FIXTURE_API extern "C" __declspec(dllexport)
#else
#define DCL_FIXTURE_API extern "C" __attribute__((visibility("default")))
#endif
DCL_FIXTURE_API void dcl_fixture_run(
    const dcl_txn::Word* words, unsigned count, int mode,
    const dcl_txn::Callbacks* callbacks, dcl_txn::Result* result) {
  *result = dcl_txn::run(words, count, mode, *callbacks);
}
DCL_FIXTURE_API void dcl_fixture_batch(
    const dcl_txn::Word* words, unsigned count, const unsigned* values, const unsigned* selected,
    const dcl_txn::Callbacks* callbacks, dcl_txn::Result* result,
    unsigned* before, unsigned* after) {
  *result = dcl_txn::run(words, count, 3, *callbacks, values, selected, before, after);
}
DCL_FIXTURE_API void dcl_fixture_restore(
    const dcl_txn::Word* words, unsigned count, const unsigned* values, const unsigned* selected,
    const unsigned* expected, const dcl_txn::Callbacks* callbacks, dcl_txn::Result* result,
    unsigned* before, unsigned* after) {
  *result = dcl_txn::run(words, count, 3, *callbacks, values, selected, before, after, expected);
}
DCL_FIXTURE_API int dcl_fixture_parse(
    int argc, const char* const* argv, const dcl_edits::Field* fields,
    unsigned count, dcl_edits::Selection* selection) {
  return dcl_edits::parse(argc, argv, fields, count, *selection);
}
