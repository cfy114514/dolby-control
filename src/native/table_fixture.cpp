#include "table_transaction.h"
#include "table_edit_args.h"
extern "C" __declspec(dllexport) void dcl_fixture_run(
    const dcl_txn::Word* words, unsigned count, int mode,
    const dcl_txn::Callbacks* callbacks, dcl_txn::Result* result) {
  *result = dcl_txn::run(words, count, mode, *callbacks);
}
extern "C" __declspec(dllexport) void dcl_fixture_batch(
    const dcl_txn::Word* words, unsigned count, const unsigned* values, const unsigned* selected,
    const dcl_txn::Callbacks* callbacks, dcl_txn::Result* result,
    unsigned* before, unsigned* after) {
  *result = dcl_txn::run(words, count, 3, *callbacks, values, selected, before, after);
}
extern "C" __declspec(dllexport) int dcl_fixture_parse(
    int argc, const char* const* argv, const dcl_edits::Field* fields,
    unsigned count, dcl_edits::Selection* selection) {
  return dcl_edits::parse(argc, argv, fields, count, *selection);
}
