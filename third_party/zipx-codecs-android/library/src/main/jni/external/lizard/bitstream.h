/*
 * Because of code deduplication we'll use zstd/bitstream.h here
 */
/* Readwide: use the already vendored Zstd portable helper headers. */
#include <zstd_deps.h>
#include <bitstream.h>

#define BIT_highbit32 ZSTD_highbit32
