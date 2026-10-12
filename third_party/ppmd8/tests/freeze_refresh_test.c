/* Readwide regression source. SPDX-License-Identifier: Apache-2.0
 * Standalone internal-model check, not linked into the app or executed here.
 * Includes the decoder model to inspect its static Refresh boundary directly;
 * no encoder, generated payload or producer-compatibility claim is involved.
 */
#define PPMD8_FREEZE_SUPPORT
#include "../Ppmd8.c"
#include <assert.h>
#include <stdlib.h>
#ifdef NDEBUG
#error This regression source requires assertions enabled
#endif

static void *test_alloc(ISzAllocPtr owner, size_t size)
{
  (void)owner;
  return malloc(size);
}

static void test_free(ISzAllocPtr owner, void *address)
{
  (void)owner;
  free(address);
}

static const ISzAlloc test_allocator = { test_alloc, test_free };

static void check_refresh(unsigned restoration, BoolInt legacy, unsigned sum,
                          unsigned requested_scale, unsigned expected_scale)
{
  CPpmd8 model;
  CPpmd8_Context *context;
  CPpmd_State *stats;
  unsigned expected_sum;
  Ppmd8_Construct(&model);
  assert(model.ReadwideFreezeRevision1 == False);
  if (!Ppmd8_Alloc(&model, (UInt32)1 << 20, &test_allocator))
    abort();
  model.ReadwideFreezeRevision1 = legacy;
  Ppmd8_Init(&model, 2, restoration);
  assert(model.ReadwideFreezeRevision1 == legacy);

  /* The root has 256 states in 128 allocated units. Model a reduction to
     two states; Refresh owns shrinking the allocation to one unit. */
  context = model.MinContext;
  stats = Ppmd8_GetStats(&model, context);
  context->NumStats = 1;
  context->Union2.SummFreq = (UInt16)sum;
  stats[0].Freq = 100;
  stats[1].Freq = 50;
  Refresh(&model, context, 128, requested_scale);
  stats = Ppmd8_GetStats(&model, context);
  assert(stats[0].Freq == ((100 + expected_scale) >> expected_scale));
  assert(stats[1].Freq == ((50 + expected_scale) >> expected_scale));
  expected_sum = ((100 + expected_scale) >> expected_scale)
      + ((50 + expected_scale) >> expected_scale)
      + ((sum - 150 + expected_scale) >> expected_scale);
  assert(context->Union2.SummFreq == expected_sum);
  Ppmd8_Free(&model, &test_allocator);
}

int main(void)
{
  unsigned restoration, sum_index;
  const unsigned sums[] = { 32767, 32768, 40000, 65535 };
  for (restoration = 0; restoration <= 3; restoration++)
    for (sum_index = 0; sum_index < sizeof(sums) / sizeof(sums[0]); sum_index++)
    {
      const unsigned sum = sums[sum_index];
      const unsigned corrected = sum >= 32768;
      /* Profile differences apply only to freeze and already-frozen state. */
      check_refresh(restoration, False, sum, 0, corrected);
      check_refresh(restoration, True, sum, 0, restoration < 2 ? corrected : 0);
      /* A caller-requested rescale is never suppressed in either profile. */
      check_refresh(restoration, False, sum, 1, 1);
      check_refresh(restoration, True, sum, 1, 1);
    }
  return 0;
}
